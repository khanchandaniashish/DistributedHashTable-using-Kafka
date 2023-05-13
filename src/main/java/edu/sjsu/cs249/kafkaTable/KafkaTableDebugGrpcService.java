package edu.sjsu.cs249.kafkaTable;

import io.grpc.stub.StreamObserver;

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
        System.out.println(replica.replicatedTable.toString());
        responseObserver.onNext(KafkaTableDebugResponse.newBuilder().build());
        responseObserver.onCompleted();
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
