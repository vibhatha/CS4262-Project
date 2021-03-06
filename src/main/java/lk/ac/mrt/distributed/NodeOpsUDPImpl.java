package lk.ac.mrt.distributed;

import lk.ac.mrt.distributed.api.Broadcastable;
import lk.ac.mrt.distributed.api.Node;
import lk.ac.mrt.distributed.api.NodeOps;
import lk.ac.mrt.distributed.api.exceptions.BootstrapException;
import lk.ac.mrt.distributed.api.exceptions.CommunicationException;
import lk.ac.mrt.distributed.api.exceptions.registration.RegistrationException;
import lk.ac.mrt.distributed.api.messages.Message;
import lk.ac.mrt.distributed.api.messages.broadcasts.MasterBroadcast;
import lk.ac.mrt.distributed.api.messages.broadcasts.MasterChangeBroadcast;
import lk.ac.mrt.distributed.api.messages.requests.*;
import lk.ac.mrt.distributed.api.messages.responses.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * UDP implementation of node operations
 *
 * @author Chathura Widanage
 */
public class NodeOpsUDPImpl extends NodeOps implements Runnable {
    protected final Logger logger = LogManager.getLogger(NodeOpsUDPImpl.class);

    protected Node bootstrapServer;

    protected DatagramSocket socket;

    //RequestResponse Handlers
    private UDPRequestResponseHandler registerRequestResponseHandler;
    private UDPRequestResponseHandler unregisterRequestResponseHandler;
    private UDPRequestResponseHandler masterRequestResponseHandler;
    private UDPRequestResponseHandler providerRequestResponseHandler;
    private UDPRequestResponseHandler transferOwnershipRequestResponseHandler;

    protected Cache<String, Broadcastable> broadcastableCache;

    public NodeOpsUDPImpl(String bootstrapServerIp, int bootstrapServerPort) {
        this.bootstrapServer = new Node(bootstrapServerIp, bootstrapServerPort);

        this.broadcastableCache = new Cache2kBuilder<String, Broadcastable>() {
        }
                .name("broadcastables_" + UUID.randomUUID().toString())
                .eternal(false)
                .expireAfterWrite(1, TimeUnit.DAYS)
                .entryCapacity(10000).build();
    }

    @Override
    protected void bootstrap() throws BootstrapException {
        try {
            socket = new DatagramSocket(this.selfNode.getPort());
        } catch (SocketException e) {
            throw new BootstrapException(e);
        }
        new Thread(this).start();
    }

    @Override
    public RegisterResponse register() throws CommunicationException, RegistrationException {
        RegisterRequest registerRequest = RegisterRequest.generate(selfNode.getIp(), selfNode.getPort(), selfNode.getUsername());
        try {
            registerRequestResponseHandler = new UDPRequestResponseHandler(bootstrapServer, registerRequest, this);
            registerRequestResponseHandler.send();
            RegisterResponse registerResponse = RegisterResponse.parse(registerRequestResponseHandler.getResponse());
            registerRequestResponseHandler = null;
            return registerResponse;
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new CommunicationException(e);
        }
    }

    @Override
    public UnregisterResponse unregister() throws CommunicationException {
        UnregisterRequest unregisterRequest = new UnregisterRequest();
        unregisterRequest.setNode(selfNode);
        unregisterRequest.setUsername(selfNode.getUsername());
        this.unregisterRequestResponseHandler =
                new UDPRequestResponseHandler(bootstrapServer, unregisterRequest, this);
        try {
            this.unregisterRequestResponseHandler.send();
            UnregisterResponse unregisterResponse = UnregisterResponse.parse(this.unregisterRequestResponseHandler.getResponse());
            this.unregisterRequestResponseHandler = null;
            return unregisterResponse;
        } catch (InterruptedException e) {
            throw new CommunicationException(e);
        }
    }

    @Override
    public void join(Set<Node> neighbours) throws CommunicationException {
        for (Node neigh : neighbours) {
            JoinRequest joinRequest = new JoinRequest();
            joinRequest.setNode(selfNode);
            this.send(neigh, joinRequest);
        }
    }

    @Override
    public void leave(Set<Node> neighbours) throws CommunicationException {
        for (Node neigh : neighbours) {
            LeaveRequest leaveRequest = new LeaveRequest();
            leaveRequest.setNode(selfNode);
            this.send(neigh, leaveRequest);
        }
    }

    @Override
    public void search(String fileName, Set<Node> neighbours) {

    }

    @Override
    public void broadcast(Broadcastable broadcastable, Set<Node> neighbours) throws CommunicationException {
        Broadcastable oldBroadcastable = broadcastableCache.get(broadcastable.getMessageId());
        if (oldBroadcastable == null || !oldBroadcastable.isBroadcasted()) {//prevent rebroadcasting same message
            //narrowcasting to all neighbours -> broadcasting to whole network
            broadcastableCache.put(broadcastable.getMessageId(), broadcastable);//put this first to avoid receiving message again to me from a neighbour
            for (Node n : neighbours) {
                this.send(n, broadcastable);
            }
            broadcastable.setBroadcasted();
        }
    }

    @Override
    public void changeMasterBroadcast(String word, Node oldMaster, Node newMaster, Set<Node> to) throws CommunicationException {
        MasterChangeBroadcast masterChangeBroadcast =
                new MasterChangeBroadcast(UUID.randomUUID().toString(), word, oldMaster, newMaster);
        this.broadcast(masterChangeBroadcast, to);
    }

    @Override
    public void broadcastIAmMaster(List<String> wordsList, Set<Node> neighbours) throws CommunicationException {
        MasterBroadcast masterBroadcast = new MasterBroadcast(UUID.randomUUID().toString(), selfNode);
        masterBroadcast.setWordsList(wordsList);
        this.broadcast(masterBroadcast, neighbours);
    }

    @Override
    public void letFalseMasterKnow(String word, Node falseMaster, Node newMaster) throws CommunicationException {
        YouNoMasterRequest youNoMasterRequest = new YouNoMasterRequest(word, newMaster);
        this.send(falseMaster, youNoMasterRequest);
    }

    @Override
    public Map<String, Node> askForMasters(Node neighbour) throws CommunicationException {
        logger.info("Asking for masters from {}", neighbour.toString());
        MasterWhoRequest masterWhoRequest = new MasterWhoRequest();
        masterWhoRequest.setNode(selfNode);
        this.masterRequestResponseHandler = new UDPRequestResponseHandler(neighbour, masterWhoRequest, this);
        try {
            this.masterRequestResponseHandler.send();
            String response = this.masterRequestResponseHandler.getResponse();
            MasterWhoResponse masterWhoResponse = MasterWhoResponse.parse(response);
            this.masterRequestResponseHandler = null;
            return masterWhoResponse.getMasters();
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new CommunicationException(e);
        }
    }

    @Override
    public void sendMasters(Node to, Map<String, Node> masters) throws CommunicationException {
        MasterWhoResponse masterWhoResponse = new MasterWhoResponse();
        masterWhoResponse.setMasters(masters);
        this.send(to, masterWhoResponse);
    }

    @Override
    public void iHaveFilesForWord(Node master, String word, List<String> fileNames) throws CommunicationException {
        IHaveRequest iHaveRequest = new IHaveRequest();
        iHaveRequest.setNode(selfNode);
        iHaveRequest.setFileNames(fileNames);
        iHaveRequest.setWord(word);
        this.send(master, iHaveRequest);
    }

    @Override
    public List<Node> getProvidersForWord(String word, Node master) throws CommunicationException {
        ProvidersRequest providersRequest = new ProvidersRequest();
        providersRequest.setWord(word);
        providersRequest.setNode(selfNode);
        this.providerRequestResponseHandler = new UDPRequestResponseHandler(master, providersRequest, this);
        try {
            this.providerRequestResponseHandler.send();
            ProvidersResponse providersResponse = ProvidersResponse.parse(this.providerRequestResponseHandler.getResponse());
            this.providerRequestResponseHandler = null;
            return providersResponse.getProviders();
        } catch (InterruptedException e) {
            e.printStackTrace();
            throw new CommunicationException(e);
        }
    }

    @Override
    public void sendProviders(Node to, String word, List<Node> providers) throws CommunicationException {
        ProvidersResponse providersResponse = new ProvidersResponse();
        providersResponse.setProviders(providers);
        providersResponse.setWord(word);
        send(to, providersResponse);
    }

    @Override
    public boolean transferResourceOwnership(String word, Node newMaster, List<Node> providers) throws CommunicationException {
        TakeMyGemsRequest takeMyGemsRequest = new TakeMyGemsRequest();
        takeMyGemsRequest.setWord(word);
        takeMyGemsRequest.setProviders(providers);
        takeMyGemsRequest.setOldMaster(selfNode);
        this.transferOwnershipRequestResponseHandler = new UDPRequestResponseHandler(newMaster, takeMyGemsRequest, this);
        try {
            this.transferOwnershipRequestResponseHandler.send();
            this.transferOwnershipRequestResponseHandler = null;
            return true;
        } catch (InterruptedException e) {
            throw new CommunicationException(e);
        }
    }

    @Override
    public void sendOwnershipTaken(Node oldMaster) throws CommunicationException {
        TakeMyGemsResponse takeMyGemsResponse = new TakeMyGemsResponse();
        this.send(oldMaster, takeMyGemsResponse);
    }

    @Override
    public void run() {
        byte buffer[];
        DatagramPacket datagramPacket;
        while (true) {
            buffer = new byte[65507];
            datagramPacket = new DatagramPacket(buffer, buffer.length);
            try {
                logger.info("Waiting for a message...");
                socket.receive(datagramPacket);
                logger.info("New data packet received from {} {}. Data :  {}", datagramPacket.getAddress().toString(),
                        datagramPacket.getPort(),
                        new String(buffer).trim());
                received(datagramPacket);
                //sending ACK //todo implement
                /*send(datagramPacket.getAddress(),
                        datagramPacket.getPort(), "GOT".getBytes());*/
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void received(DatagramPacket datagramPacket) {
        String msg = new String(datagramPacket.getData(), 0, datagramPacket.getLength());
        StringTokenizer stringTokenizer = new StringTokenizer(msg, " ");
        String length = stringTokenizer.nextToken();
        String command = stringTokenizer.nextToken();
        int statusCode = 0;
        try {
            switch (command) {
                case "REGOK":
                    //handle register response
                    if (this.registerRequestResponseHandler != null) {
                        registerRequestResponseHandler.setResponse(msg);
                    }
                    break;
                case "UNROK":
                    logger.debug("UNREGOK revieved");
                    if (this.unregisterRequestResponseHandler != null) {
                        this.unregisterRequestResponseHandler.setResponse(msg);
                    }
                    break;
                case "LEAVE":
                    LeaveRequest leaveRequest = LeaveRequest.parse(msg);
                    statusCode = this.commandListener.onLeaveRequest(leaveRequest);
                    LeaveResponse leaveResponse = new LeaveResponse();
                    leaveResponse.setValue(statusCode);
                    send(leaveRequest.getNode(), leaveResponse);
                    break;
                case "JOIN":
                    JoinRequest joinRequest = JoinRequest.parse(msg);
                    statusCode = this.commandListener.onJoinRequest(joinRequest);
                    JoinResponse joinResponse = new JoinResponse();
                    joinResponse.setValue(statusCode);
                    send(joinRequest.getNode(), joinResponse);
                    break;//todo LEAVEOK, JOINOK
                case "MEMASTER":
                    MasterBroadcast masterBroadcast = MasterBroadcast.parse(msg);
                    commandListener.onMasterBroadcast(masterBroadcast);
                    break;
                case "MENOMASTER":
                    MasterChangeBroadcast masterChangeBroadcast = MasterChangeBroadcast.parse(msg);
                    commandListener.onMasterChangeBroadcast(masterChangeBroadcast);
                    break;
                case "UNOMASTER":
                    YouNoMasterRequest youNoMasterRequest = YouNoMasterRequest.parse(msg);
                    commandListener.onYouNoMasterRequest(youNoMasterRequest);
                    break;
                case "MASTERWHO":
                    MasterWhoRequest masterWhoRequest = MasterWhoRequest.parse(msg);
                    Map<String, Node> masterNodes = commandListener.onMasterWhoRequest(masterWhoRequest);
                    this.sendMasters(masterWhoRequest.getNode(), masterNodes);
                    break;
                case "MASTERS":
                    if (this.masterRequestResponseHandler != null) {
                        this.masterRequestResponseHandler.setResponse(msg);
                    }
                    break;
                case "PROVFOR":
                    ProvidersRequest providersRequest = ProvidersRequest.parse(msg);
                    List<Node> providersList = this.commandListener.onProvidersRequest(providersRequest);
                    this.sendProviders(providersRequest.getNode(), providersRequest.getWord(), providersList);
                    break;
                case "PROVS":
                    if (this.providerRequestResponseHandler != null) {
                        this.providerRequestResponseHandler.setResponse(msg);
                    }
                    break;
                case "TAKEMYGEMS":
                    TakeMyGemsRequest takeMyGemsRequest = TakeMyGemsRequest.parse(msg);
                    this.commandListener.onTakeMyGemsRequest(takeMyGemsRequest);
                    this.sendOwnershipTaken(takeMyGemsRequest.getOldMaster());
                    break;
                case "TAKEMYGEMSOK":
                    if (this.transferOwnershipRequestResponseHandler != null) {
                        this.transferOwnershipRequestResponseHandler.setResponse(msg);
                    }
                    break;
                case "IHAVE":
                    IHaveRequest iHaveRequest = IHaveRequest.parse(msg);
                    this.commandListener.onIHaveRequest(iHaveRequest);
                    break;
            }
        } catch (Exception ex) {//todo make this better
            //catching any error in order to not harm the while loop
            logger.error("Error in executing received message", ex);
        }
    }

    public void send(Node node, Message msg) throws CommunicationException {
        send(node.getIp(), node.getPort(), msg);
    }

    public void send(String ip, int port, Message msg) throws CommunicationException {
        try {
            send(InetAddress.getByName(ip), port, msg.getSendableString().getBytes());
        } catch (UnknownHostException e) {
            logger.error("Unknown host", e);
            throw new CommunicationException(e);
        }
    }

    public void send(Node node, Broadcastable broadcastable) throws CommunicationException {
        try {
            send(InetAddress.getByName(node.getIp()), node.getPort(), broadcastable.getBroadcastMessage().getBytes());
        } catch (UnknownHostException e) {
            logger.error("Unknown host", e);
            throw new CommunicationException(e);
        }
    }

    public void send(InetAddress inetAddress, int port, byte[] msgBuffer) throws CommunicationException {
        if (msgBuffer.length > 65507) {//just to make sure
            logger.error("UDP Packet size exceeded.");
            throw new CommunicationException("UDP Packet size exceeded. Not supported by this application yet.");
        }
        DatagramPacket datagramPacket = new DatagramPacket(msgBuffer, msgBuffer.length);
        datagramPacket.setAddress(inetAddress);
        datagramPacket.setPort(port);
        logger.info("Sending message '{}' to {}:{}", new String(msgBuffer), inetAddress.getHostAddress(), port);
        try {
            socket.send(datagramPacket);
        } catch (IOException e) {
            logger.error("Error in sending datagram packet", e);
            throw new CommunicationException(e);
        }
    }
}
