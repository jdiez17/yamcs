package org.yamcs.parameterarchive;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.Processor;
import org.yamcs.parameter.AggregateValue;
import org.yamcs.parameter.ParameterConsumer;
import org.yamcs.parameter.Value;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.utils.LoggingUtils;
import org.yamcs.utils.SortedIntArray;
import org.yamcs.utils.TimeEncoding;

/**
 * Archive filler that creates segments of max size .
 * 
 * @author nm
 *
 */
class ArchiveFillerTask implements ParameterConsumer {
    final ParameterArchive parameterArchive;
    final private Logger log;

    long numParams = 0;
    static int DEFAULT_MAX_SEGMENT_SIZE = 5000;
    static MemoryPoolMXBean memoryBean = getMemoryBean();
    // ParameterGroup_id -> PGSegment
    protected Map<Integer, PGSegment> pgSegments = new HashMap<>();
    protected final ParameterIdDb parameterIdMap;
    protected final ParameterGroupIdDb parameterGroupIdMap;

    // ignore any data older than this
    protected long collectionSegmentStart;

    long threshold = 60000;
    int maxSegmentSize;
    private Processor processor;
    boolean aborted = false;

    public ArchiveFillerTask(ParameterArchive parameterArchive, int maxSegmentSize) {
        this.parameterArchive = parameterArchive;
        this.parameterIdMap = parameterArchive.getParameterIdDb();
        this.parameterGroupIdMap = parameterArchive.getParameterGroupIdDb();
        log = LoggingUtils.getLogger(this.getClass(), parameterArchive.getYamcsInstance());
        this.maxSegmentSize = maxSegmentSize;
        log.debug("Archive filler task maxSegmentSize: {} ", maxSegmentSize);
    }

    void setCollectionSegmentStart(long collectionSegmentStart) {
        this.collectionSegmentStart = collectionSegmentStart;
    }

    /**
     * adds the parameters to the pgSegments structure
     * 
     * parameters older than collectionSegmentStart are ignored.
     * 
     * 
     * @param items
     * @return
     */
    void processParameters(List<ParameterValue> items) {
        Map<Long, SortedParameterList> m = new HashMap<>();
        for (ParameterValue pv : items) {
            long t = pv.getGenerationTime();
            if (t < collectionSegmentStart) {
                continue;
            }
            if (pv.getParameterQualifiedNamed() == null) {
                log.warn("No qualified name for parameter value {}, ignoring", pv);
                continue;
            }
            if (pv.getEngValue() instanceof AggregateValue) {
                // log.warn("{}: aggregate values not supported, ignoring", pv.getParameterQualifiedNamed());
                continue;
            }
            SortedParameterList l = m.get(t);
            if (l == null) {
                l = new SortedParameterList();
                m.put(t, l);
            }
            l.add(pv);
        }
        boolean needsFlush = false;
        long maxTimestamp = collectionSegmentStart;
        for (Map.Entry<Long, SortedParameterList> entry : m.entrySet()) {
            long t = entry.getKey();
            SortedParameterList pvList = entry.getValue();
            if (processParameters(t, pvList)) {
                needsFlush = true;
            }
            if (t > maxTimestamp) {
                maxTimestamp = t;
            }
        }
        if (needsFlush) {
            consolidateAndWriteToArchive(collectionSegmentStart);
            collectionSegmentStart = maxTimestamp + 1;
        }
    }

    private boolean processParameters(long t, SortedParameterList pvList) {
        numParams += pvList.size();
        try {
            int parameterGroupId = parameterGroupIdMap.createAndGet(pvList.parameterIdArray);

            PGSegment pgs = pgSegments.get(parameterGroupId);
            if (pgs == null) {
                pgs = new PGSegment(parameterGroupId, collectionSegmentStart, pvList.parameterIdArray, maxSegmentSize);
                pgSegments.put(parameterGroupId, pgs);
            }

            pgs.addRecord(t, pvList.sortedPvList);
            return pgs.size() > maxSegmentSize;

        } catch (RocksDBException e) {
            log.error("Error processing parameters", e);
            return false;
        }
    }

    void flush() {
        if(pgSegments!=null) {
            consolidateAndWriteToArchive(collectionSegmentStart);
        }
    }

    /**
     * writes data into the archive
     * 
     * @param pgList
     */
    protected void consolidateAndWriteToArchive(long segStart) {
        log.debug("writing to archive semgent starting at {} with {} groups", TimeEncoding.toString(segStart),
                pgSegments.size());

        for (PGSegment pgs : pgSegments.values()) {
            pgs.consolidate();
        }
        try {
            parameterArchive.writeToArchive(segStart, pgSegments.values());
        } catch (RocksDBException | IOException e) {
            log.error("failed to write data to the archive", e);
        }
        pgSegments.clear();
    }

    @Override
    public void updateItems(int subscriptionId, List<ParameterValue> items) {
        if(oomImminent()) {
            return;
        }

        long t = items.get(0).getGenerationTime();
        long t1 = SortedTimeSegment.getMinSegmentStart(t);

        if (t1 > collectionSegmentStart) {
            if (!pgSegments.isEmpty()) {
                consolidateAndWriteToArchive(collectionSegmentStart);
            }
            collectionSegmentStart = t1;
        }

        processParameters(items);
    }

    public long getNumProcessedParameters() {
        return numParams;
    }

    /* builds incrementally a list of parameter id and parameter value, sorted by parameter ids */
    class SortedParameterList {
        SortedIntArray parameterIdArray = new SortedIntArray();
        List<ParameterValue> sortedPvList = new ArrayList<>();

        void add(ParameterValue pv) {
            String fqn = pv.getParameterQualifiedNamed();
            Value engValue = pv.getEngValue();
            if (engValue == null) {
                log.warn("Ignoring parameter without engineering value: {} ", pv.getParameterQualifiedNamed());
                return;
            }
            Value rawValue = pv.getRawValue();
            Type engType = engValue.getType();
            Type rawType = (rawValue == null) ? null : rawValue.getType();
            int parameterId = parameterIdMap.createAndGet(fqn, engType, rawType);

            int pos = parameterIdArray.insert(parameterId);
            sortedPvList.add(pos, pv);
        }

        public int size() {
            return parameterIdArray.size();
        }
    }

    private boolean oomImminent() {
        if (memoryBean != null && memoryBean.isCollectionUsageThresholdExceeded()) {
            aborted = true;
            
            String msg = "Aborting parameter archive filling due to imminent out of memory. Consider decreasing the maxSegmentSize (current value is "+maxSegmentSize+").";
            log.error(msg);
            pgSegments = null;
            processor.stopAsync();
            System.gc();
            return true;
        }
        return false;
    }

    static MemoryPoolMXBean getMemoryBean() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported() && pool.getName().toLowerCase().contains("old")) {
                long threshold = (long) Math.floor(pool.getUsage().getMax() * 0.90);
                pool.setCollectionUsageThreshold(threshold);
                return pool;
            }
        }
        return null;
    }

    public void setProcessor(Processor proc) {
        this.processor = proc;
    }
    
    /**
     * If the archive filling has been aborted (due to imminent OOM) this returns true 
     */
    boolean isAborted() {
        return aborted;
    }
}
