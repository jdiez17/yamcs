package org.yamcs;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.yamcs.api.EventProducerFactory;
import org.yamcs.api.ws.WebSocketClient;
import org.yamcs.api.ws.WebSocketClientCallback;
import org.yamcs.api.ws.YamcsConnectionProperties;
import org.yamcs.archive.PacketWithTime;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.Alarms.Alarm;
import org.yamcs.protobuf.Commanding.ArgumentAssignmentType;
import org.yamcs.protobuf.Commanding.CommandHistoryEntry;
import org.yamcs.protobuf.Commanding.CommandType;
import org.yamcs.protobuf.Commanding.SendCommandRequest;
import org.yamcs.protobuf.Pvalue.ParameterData;
import org.yamcs.protobuf.Web.WebSocketServerMessage.WebSocketSubscriptionData;
import org.yamcs.protobuf.Yamcs.Event;
import org.yamcs.protobuf.Yamcs.NamedObjectId;
import org.yamcs.protobuf.Yamcs.StreamData;
import org.yamcs.protobuf.Yamcs.TimeInfo;
import org.yamcs.protobuf.YamcsManagement.ClientInfo;
import org.yamcs.protobuf.YamcsManagement.ProcessorInfo;
import org.yamcs.protobuf.YamcsManagement.Statistics;
import org.yamcs.security.Privilege;
import org.yamcs.security.UsernamePasswordToken;
import org.yamcs.tctm.TmPacketSource;
import org.yamcs.tctm.TmSink;
import org.yamcs.utils.FileUtils;
import org.yamcs.utils.HttpClient;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.xtce.SequenceContainer;

import com.google.common.util.concurrent.AbstractService;
import com.google.protobuf.MessageLite;

import io.protostuff.JsonIOUtil;
import io.protostuff.Schema;

public abstract class AbstractIntegrationTest {
    PacketProvider packetProvider;
    YamcsConnectionProperties ycp = new YamcsConnectionProperties("localhost", 9190, "IntegrationTest");
    MyWsListener wsListener;
    WebSocketClient wsClient;
    HttpClient httpClient;
    UsernamePasswordToken admin = new UsernamePasswordToken("admin", "rootpassword");;
    UsernamePasswordToken currentUser = null;
    RefMdbPacketGenerator packetGenerator;


    @BeforeClass
    public static void beforeClass() throws Exception {
      //  enableDebugging();
        setupYamcs();
    }

    static void enableDebugging() {
        Logger.getLogger("org.yamcs").setLevel(Level.ALL);
    }
    
    @Before
    public void before() throws InterruptedException {

        if(Privilege.getInstance().isEnabled())  {
            currentUser = admin;
        }

        packetProvider = PacketProvider.instance;
        assertNotNull(packetProvider);
        wsListener = new MyWsListener();
        if(currentUser != null)
            wsClient = new WebSocketClient(ycp, wsListener, currentUser.getUsername(), currentUser.getPasswordS());
        else
            wsClient = new WebSocketClient(ycp, wsListener);
        wsClient.setUserAgent("it-junit");
        wsClient.connect();
        assertTrue(wsListener.onConnect.tryAcquire(5, TimeUnit.SECONDS));
        httpClient = new HttpClient();
        packetGenerator = packetProvider.mdbPacketGenerator;
        packetGenerator.setGenerationTime(TimeEncoding.INVALID_INSTANT);

        //        ClientInfo cinfo = wsListener.clientInfoList.poll(5, TimeUnit.SECONDS);
        //        System.out.println("got cinfo:"+cinfo);
        //        assertNotNull(cinfo);
    }


    private static void setupYamcs() throws Exception {
        File dataDir=new File("/tmp/yamcs-IntegrationTest-data");

        FileUtils.deleteRecursively(dataDir.toPath());

        EventProducerFactory.setMockup(true);
        YConfiguration.setup("IntegrationTest");
        ManagementService.setup(false, false);
        org.yamcs.yarch.management.ManagementService.setup(false);
        YamcsServer.setupHornet();
        YamcsServer.setupYamcsServer();
    }


    SendCommandRequest getCommand(String cmdName, int seq, String... args) {
        NamedObjectId cmdId = NamedObjectId.newBuilder().setName(cmdName).build();

        CommandType.Builder cmdb = CommandType.newBuilder().setOrigin("IntegrationTest").setId(cmdId).setSequenceNumber(seq);
        for(int i =0 ;i<args.length; i+=2) {
            cmdb.addArguments(ArgumentAssignmentType.newBuilder().setName(args[i]).setValue(args[i+1]).build());
        }

        return SendCommandRequest.newBuilder().addCommand(cmdb.build()).build();
    }

    @After
    public void after() throws InterruptedException {
        wsClient.disconnect();
        assertTrue(wsListener.onDisconnect.tryAcquire(5, TimeUnit.SECONDS));
    }

    @AfterClass
    public static void shutDownYamcs()  throws Exception {
        YamcsServer.shutDown();
        YamcsServer.stopHornet();
    }

    
    <T extends MessageLite> String toJson(T msg, Schema<T> schema) throws IOException {
        StringWriter writer = new StringWriter();
        JsonIOUtil.writeTo(writer, msg, schema, false);
        return writer.toString();
    }

    <T extends MessageLite.Builder> T fromJson(String jsonstr, Schema<T> schema) throws IOException {
        StringReader reader = new StringReader(jsonstr);
        T msg = schema.newMessage();
        JsonIOUtil.mergeFrom(reader, msg, schema, false);
        return msg;
    }

    class MyWsListener implements WebSocketClientCallback {
        Semaphore onConnect = new Semaphore(0);
        Semaphore onDisconnect = new Semaphore(0);

        LinkedBlockingQueue<NamedObjectId> invalidIdentificationList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ParameterData> parameterDataList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<CommandHistoryEntry> cmdHistoryDataList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ClientInfo> clientInfoList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<ProcessorInfo> processorInfoList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Statistics> statisticsList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Alarm> alarmList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Event> eventList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<StreamData> streamDataList = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<TimeInfo> timeInfoList = new LinkedBlockingQueue<>();

        int count =0;
        @Override
        public void connected() {
            onConnect.release();

        }

        @Override
        public void disconnected() {
            onDisconnect.release();
        }
        
        @Override
        public void onInvalidIdentification(NamedObjectId id) {
            invalidIdentificationList.add(id);
        }

        @Override
        public void onMessage(WebSocketSubscriptionData data) {
            switch (data.getType()) {
            case PARAMETER:
                count++;
                if((count %1000) ==0 ){
                    System.out.println("received pdata "+count);
                }

                parameterDataList.add(data.getParameterData());
                break;
            case CMD_HISTORY:
                //System.out.println("COMMAND HISTORY-------------"+cmdhistData);
                cmdHistoryDataList.add(data.getCommand());
                break;
            case CLIENT_INFO:
                clientInfoList.add(data.getClientInfo());
                break;
            case PROCESSOR_INFO:
                processorInfoList.add(data.getProcessorInfo());
                break;
            case PROCESSING_STATISTICS:
                statisticsList.add(data.getStatistics());
                break;
            case ALARM:
                alarmList.add(data.getAlarm());
                break;
            case EVENT:
                eventList.add(data.getEvent());
                break;
            case STREAM_DATA:
                streamDataList.add(data.getStreamData());
                break;
            case TIME_INFO:
                timeInfoList.add(data.getTimeInfo());
                break;
            default:
                throw new IllegalArgumentException("Unexpected type " + data.getType());
            }
        }
    }
    public static class PacketProvider extends AbstractService implements TmPacketSource, TmProcessor {
        static volatile PacketProvider instance;
        RefMdbPacketGenerator mdbPacketGenerator = new RefMdbPacketGenerator();
        TmSink tmSink;

        public PacketProvider(String yinstance, String name, String spec) {
            instance = this;
        }
        @Override
        public String getLinkStatus() {
            return "OK";
        }
        @Override
        public String getDetailedStatus() {
            return "OK";
        }
        @Override
        public void enable() {

        }
        @Override
        public void disable() {

        }
        @Override
        public boolean isDisabled() {
            return false;
        }
        @Override
        public long getDataCount() {
            return 0;
        }

        @Override
        public void setTmSink(TmSink tmSink) {
            this.tmSink = tmSink;                   
        }

        @Override
        public boolean isArchiveReplay() {
            return false;
        }
        @Override
        protected void doStart() {
            mdbPacketGenerator.init(null, this);
            notifyStarted();
        }
        @Override
        protected void doStop() {
            notifyStopped();
        }
        @Override
        public void processPacket(PacketWithTime pwrt) {
            tmSink.processPacket(pwrt);
        }
        @Override
        public void processPacket(PacketWithTime pwrt, SequenceContainer rootContainer) {
            tmSink.processPacket(pwrt);
        }
        @Override
        public void finished() {

        }
    }
}
