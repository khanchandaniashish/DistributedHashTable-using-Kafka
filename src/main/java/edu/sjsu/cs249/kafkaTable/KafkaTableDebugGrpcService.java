package edu.sjsu.cs249.kafkaTable;

import io.grpc.stub.StreamObserver;

import static edu.sjsu.cs249.kafkaTable.Replica.*;

/**
 * @author ashish
 */
public class KafkaTableDebugGrpcService extends KafkaTableDebugGrpc.KafkaTableDebugImplBase {

    Replica replica;

    public KafkaTableDebugGrpcService(Replica replica) {
        this.replica = replica;
    }

    /**
     * @param request
     * @param responseObserver
     */
    @Override
    public void debug(KafkaTableDebugRequest request, StreamObserver<KafkaTableDebugResponse> responseObserver) {
        synchronized (replica.replicatedTable) {
            System.out.println(replica.replicatedTable.toString());
            System.out.println(ClientTxnLog);
            System.out.println("Debug GRPC called");
            Snapshot snapshot = Snapshot.newBuilder().setReplicaId(name).putAllTable(replica.replicatedTable.hashtable).setOperationsOffset(lastSeenOperationsOffset).putAllClientCounters(ClientTxnLog).setSnapshotOrderingOffset(lastSeenOrderingOffset).build();
            responseObserver.onNext(KafkaTableDebugResponse.newBuilder().setSnapshot(snapshot).build());
            responseObserver.onCompleted();
            System.out.println("Debug GRPC returned");
        }
    }

    /**
     * @param request
     * @param responseObserver
     */
    @Override
    public void exit(ExitRequest request, StreamObserver<ExitResponse> responseObserver) {
        System.out.println("RUN FOREST RUN!");
        System.exit(0);
    }
}
