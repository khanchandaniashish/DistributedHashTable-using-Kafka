package edu.sjsu.cs249.kafkaTable;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

import static edu.sjsu.cs249.kafkaTable.Replica.*;

/**
 * @author ashish
 */
public class KafkaOperationsConsumer extends Thread {
    /**
     * If this thread was constructed using a separate
     * {@code Runnable} run object, then that
     * {@code Runnable} object's {@code run} method is called;
     * otherwise, this method does nothing and returns.
     * <p>
     * Subclasses of {@code Thread} should override this method.
     *
     * @see #//start()
     * @see #//stop()
     */
    String bootstrapServer;
    ReplicatedTable replicatedTable;
    HashMap<ClientXid, StreamObserver<IncResponse>> incResponseHashMap;

    HashMap<ClientXid, StreamObserver<GetResponse>> getResponseHashMap;

    KafkaSnapshotOrderingConsumer kafkaSnapshotOrderingConsumer;

    KafkaSnapshotConsumer kafkaSnapshotConsumer;

    KafkaOperationsConsumer(String bootstrapServer, ReplicatedTable replicatedTable, HashMap<ClientXid, StreamObserver<IncResponse>> incResponseHashMap, KafkaSnapshotOrderingConsumer kafkaSnapshotOrderingConsumer, KafkaSnapshotConsumer kafkaSnapshotConsumer,HashMap<ClientXid, StreamObserver<GetResponse>> getResponseHashMap) {
        this.bootstrapServer = bootstrapServer;
        this.replicatedTable = replicatedTable;
        this.incResponseHashMap = incResponseHashMap;
        this.getResponseHashMap = getResponseHashMap;
        this.kafkaSnapshotOrderingConsumer= kafkaSnapshotOrderingConsumer;
        this.kafkaSnapshotConsumer = kafkaSnapshotConsumer;
    }

    @Override
    public void run() {
        System.out.println("STARTINGGG OPDS TOPICC");
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, name + "ConsumerGroup");
        Consumer<String, byte[]> consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());

        var sem = new Semaphore(0);
        consumer.subscribe(List.of(OPERATIONS_TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
                System.out.println("Didn't expect the revoke!");
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
                System.out.println("Partition assigned");
                System.out.println("SEEKING");
                collection.forEach(t -> consumer.seek(t, 0));
                System.out.println("SEEK DONE");
                sem.release();
            }
        });
        System.out.println("first poll count: " + consumer.poll(0).count());
        System.out.println("lastSeenOperationsOffset is :" + lastSeenOperationsOffset);
        lastSeenOperationsOffset = lastSeenOperationsOffset == -1 ? 0 : lastSeenOperationsOffset;
        System.out.println("lastSeenOperationsOffset after update is :" + lastSeenOperationsOffset);
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("Ready to consume at " + new Date());
        System.out.println("Consumer successfully initialized");

        while (true) {
            synchronized (replicatedTable) {
                ConsumerRecords<String, byte[]> consumerRecords = consumer.poll(Duration.ofSeconds(1));
                consumerRecords.forEach(record -> {
                    System.out.printf("offset = %d, key = %s, value = %s%n", lastSeenOperationsOffset, record.key(), Arrays.toString(record.value()));
                    if (record.offset() >= lastSeenOperationsOffset) {
                        lastSeenOperationsOffset = record.offset();
                        PublishedItem message = null;
                        try {
                            message = PublishedItem.parseFrom(record.value());
                            if (message.hasInc()) {
                                doInc(message);
                            } else {
                                Integer res = doGet(message);
                                System.out.println("DoGet result = " + res);
                            }
                        } catch (InvalidProtocolBufferException e) {
                            System.out.println("INVALID MESSAGE TYPE Recieved");
                            e.printStackTrace();
                        }
                        System.out.println("Consumed message : " + message);
                        if (lastSeenOperationsOffset % Replica.snapshotDecider == 0) {
                            System.out.println("It's my time to take a snapshot as lastSeenOffset " + lastSeenOperationsOffset + " % " + snapshotDecider + " is zero");
                            if (kafkaSnapshotOrderingConsumer.isTimeToPublishSnapshot()) {
                                System.out.println("it is indeed time to take a snapshot");
                                kafkaSnapshotConsumer.publishSnapshot();
                                System.out.println("it is done! took a snapshot and published it");
                            }
                        }
                    } else {
                        System.out.println("Ignoring the message as the offset recieved from kafka operations topic was less than my lastSeenOffset and this wont be counted in the ops modding");
                    }
                });
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }


    private Integer doGet(PublishedItem message) {
        Integer res =  null;
        if (isValidClientReq(message.getGet().getXid())) {
            res = replicatedTable.get(message.getGet().getKey());
            if (Objects.isNull(res)) res = 0;
            System.out.println("Adding to ClientTxnLog");
            ClientTxnLog.put(message.getGet().getXid().getClientid(), message.getGet().getXid().getCounter());
            System.out.println("Added to ClientTxnLog");
            if (getResponseHashMap.containsKey(message.getGet().getXid())) {
                System.out.println("RETURNING GET ON COMPLETED for client id : " + message.getGet().getXid().getClientid() + " with counter :" + message.getGet().getXid().getCounter());
                StreamObserver<GetResponse> observer = getResponseHashMap.remove(message.getGet().getXid());
                    observer.onNext(GetResponse.newBuilder().setValue(res).build());
                    observer.onCompleted();
                }
        } else {
            System.out.println("DUPLICATE GET REQ RECVD from kafka consumer..ignoringg");
            if (getResponseHashMap.containsKey(message.getGet().getXid())) {
                StreamObserver<GetResponse> observer = getResponseHashMap.remove(message.getGet().getXid());
                    observer.onNext(GetResponse.newBuilder().build());
                    observer.onCompleted();
            }
        }
        return res;
    }

    private void doInc(PublishedItem message) {
        if (isValidClientReq(message.getInc().getXid())) {
            replicatedTable.inc(message.getInc().getKey(), message.getInc().getIncValue());
            System.out.println("Adding to ClientTxnLog");
            ClientTxnLog.put(message.getInc().getXid().getClientid(), message.getInc().getXid().getCounter());
            System.out.println("Added to ClientTxnLog");
            if (incResponseHashMap.containsKey(message.getInc().getXid())) {
                System.out.println("RETURNING INC ONCOMPLETED for client id : " + message.getInc().getXid().getClientid() + " with counter :" + message.getInc().getXid().getCounter());
                StreamObserver<IncResponse> observer = incResponseHashMap.remove(message.getInc().getXid());
                    observer.onNext(IncResponse.newBuilder().build());
                    observer.onCompleted();
//                    incResponseHashMap.remove(message.getInc().getXid());
            }
        } else {
            System.out.println("DUPLICATE INC REQ RECVD from kafka consumer..ignoringg");
            if (incResponseHashMap.containsKey(message.getInc().getXid())) {
                    StreamObserver<IncResponse> observer = incResponseHashMap.remove(message.getInc().getXid());
                    observer.onNext(IncResponse.newBuilder().build());
                    observer.onCompleted();
//                    incResponseHashMap.remove(message.getInc().getXid());
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
        System.out.println("Valid Client request from Client : " + clientXid.getClientid() + " with counter : " + clientXid.getCounter());
        return true;
    }
}
