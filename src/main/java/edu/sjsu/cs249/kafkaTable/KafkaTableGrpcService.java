package edu.sjsu.cs249.kafkaTable;

import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.Objects;

import static edu.sjsu.cs249.kafkaTable.Replica.OPERATIONS_TOPIC;

/**
 * @author ashish
 */
public class KafkaTableGrpcService extends KafkaTableGrpc.KafkaTableImplBase {
    ReplicatedTable replicatedTable;
    Replica replica;
    HashMap<String, Integer> ClientTxnLog;

    HashMap<ClientXid, StreamObserver<IncResponse>> incResponseHashMap;

    HashMap<ClientXid, StreamObserver<GetResponse>> getResponseHashMap;

    public KafkaTableGrpcService(Replica replica, ReplicatedTable replicatedTable) {
        this.replicatedTable = replicatedTable;
        this.replica = replica;
        ClientTxnLog = replica.ClientTxnLog;
        incResponseHashMap = replica.incResponseHashMap;
        getResponseHashMap = replica.getResponseHashMap;
    }

    /**
     * @param request
     * @param responseObserver
     */
    @Override
    public void inc(IncRequest request, StreamObserver<IncResponse> responseObserver) {
        synchronized (replicatedTable) {
            //Check if Client req is valid
            ClientXid clientXid = request.getXid();

            if (isValidClientReq(clientXid)) {
                // publish to OP topic
                System.out.println("publishing INC req to kafka OP topic : " + OPERATIONS_TOPIC);
                PublishedItem publishedItem = PublishedItem.newBuilder().setInc(request).build();
                replica.sendMessage(OPERATIONS_TOPIC, publishedItem);
                incResponseHashMap.put(clientXid, responseObserver);
                System.out.println("published inc req to kafka op topic");
            } else {
                System.out.println("RETURNING INC ONCOMPLETED for INVALID req with client id : " + clientXid.getClientid() + " with counter :" + clientXid.getCounter());
                responseObserver.onNext(IncResponse.newBuilder().build());
                responseObserver.onCompleted();
            }
        }
    }


    /**
     * @param request
     * @param responseObserver
     */
    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        //publish to OP topic
        synchronized (replicatedTable) {
            ClientXid clientXid = request.getXid();
            if (isValidClientReq(clientXid)) {
                // publish to OP topic
                System.out.println("publishing GET req to kafka OP topic : " + OPERATIONS_TOPIC);
                PublishedItem publishedItem = PublishedItem.newBuilder().setGet(request).build();
                getResponseHashMap.put(clientXid, responseObserver);

                System.out.println("publishing get req to kafka OP topic : " + OPERATIONS_TOPIC);
                replica.sendMessage(OPERATIONS_TOPIC, publishedItem);
                System.out.println("published get req to kafka OP topic");
            } else {
                System.out.println("RETURNING get ONCOMPLETED for INVALID req with client id : " + clientXid.getClientid() + " with counter :" + clientXid.getCounter());
                Integer res = replicatedTable.hashtable.containsKey(request.getKey()) ? replicatedTable.hashtable.get(request.getKey()) : 0;
                if (Objects.isNull(res)) res = 0;
                responseObserver.onNext(GetResponse.newBuilder().setValue(res).build());
                responseObserver.onCompleted();
            }
        }
    }

    public boolean isValidClientReq(ClientXid clientXid) {
        System.out.println("Validating Client request from Client : " + clientXid.getClientid() + " with counter : " + clientXid.getCounter());
        if (ClientTxnLog.containsKey(clientXid.getClientid())) {
            // for same client id the counter has to be > than the last seen counter
            // && should not have any other pending requests
            System.out.println("Comparing " + clientXid.getCounter() + "  with counter from map: " + ClientTxnLog.get(clientXid.getClientid()));
            if (clientXid.getCounter() <= ClientTxnLog.get(clientXid.getClientid())) {
                System.out.println("IGNORED : Invalid Client request from Client : " + clientXid.getClientid() + " with counter : " + clientXid.getCounter());
                return false;
            }
        }
        // received the req already
        //TODO CHECK THIS
        //ClientTxnLog.put(clientXid.getClientid(), clientXid.getCounter());
        System.out.println("Valid Client request from Client : " + clientXid.getClientid() + " with counter : " + clientXid.getCounter());
        return true;
    }
}
