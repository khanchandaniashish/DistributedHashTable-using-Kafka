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
    //TODO GET Response builder ?

    KafkaOperationsConsumer(String bootstrapServer, ReplicatedTable replicatedTable, HashMap<ClientXid, StreamObserver<IncResponse>> incResponseHashMap) {
        this.bootstrapServer = bootstrapServer;
        this.replicatedTable = replicatedTable;
        this.incResponseHashMap = incResponseHashMap;
    }

    @Override
    public void run() {
        System.out.println("STARTINGGG OPDS TOPICC");
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        //TODO: Parameterize Group ID later
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "AshishConsumerGroup");
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
        //TODO Added +1 ?
        System.out.println("lastSeenOrderingOffset is :"+ lastSeenOrderingOffset);
        lastSeenOperationsOffset = Objects.isNull(lastSeenOperationsOffset) ? 0 : lastSeenOperationsOffset;
        System.out.println("first poll count: " + consumer.poll(lastSeenOperationsOffset +1).count());
        try {
            sem.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        System.out.println("Ready to consume at " + new Date());
        System.out.println("Consumer successfully initialized");

        while (true) {
            ConsumerRecords<String, byte[]> consumerRecords = consumer.poll(Duration.ofSeconds(1));

            consumerRecords.forEach(record -> {
                lastSeenOperationsOffset = record.offset();
                System.out.printf("offset = %d, key = %s, value = %s%n", lastSeenOperationsOffset, record.key(), Arrays.toString(record.value()));
                PublishedItem message = null;
                try {
//                    message = SimpleMessage.parseFrom(record.value());
                    message = PublishedItem.parseFrom(record.value());
                    if (message.hasInc()) {
                        doInc(message);
                    } else {
                        Integer res = doGet(message);
                        System.out.println("DoGet result = " + res);
                    }
                    //TODO handle returning the response observer if you were the originator back to GRPC

                } catch (InvalidProtocolBufferException e) {
                    System.out.println("INVALID MESSAGE TYPE Recieved");
                    e.printStackTrace();
                }
                System.out.println("Consumed message : " + message);
                if (lastSeenOperationsOffset % Replica.snapshotDecider == 0) {
                    System.out.println("It's my time to take a snapshot as lastSeenOffset " + lastSeenOperationsOffset + " % " + snapshotDecider + " is zero");
                    //TODO Call snapshot decider here
                }
            });
        }

    }

    private Integer doGet(PublishedItem message) {
        return replicatedTable.get(message.getGet().getKey());
    }

    private void doInc(PublishedItem message) {
        replicatedTable.inc(message.getInc().getKey(), message.getInc().getIncValue());
        if (incResponseHashMap.containsKey(message.getInc().getXid())) {
            System.out.println("RETURNING INC ONCOMPLETED for client id : " + message.getInc().getXid().getClientid() + " with counter :" + message.getInc().getXid().getCounter());
            StreamObserver<IncResponse> observer = incResponseHashMap.get(message.getInc().getXid());
            observer.onNext(IncResponse.newBuilder().build());
            observer.onCompleted();
        }
    }
}
