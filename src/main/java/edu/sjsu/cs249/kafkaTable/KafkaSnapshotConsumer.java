package edu.sjsu.cs249.kafkaTable;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

import static edu.sjsu.cs249.kafkaTable.Replica.*;

/**
 * @author ashish
 */
public class KafkaSnapshotConsumer {
    String bootstrapServer;
    ReplicatedTable replicatedTable;
    Long seekTime;
    Producer producer;

    KafkaSnapshotConsumer(String bootstrapServer, ReplicatedTable replicatedTable, Long seekTime, Producer operationsProducer) {
        this.bootstrapServer = bootstrapServer;
        this.replicatedTable = replicatedTable;
        this.seekTime = seekTime;
        this.producer = operationsProducer;
    }

//    @Override
    public void run() {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        //TODO: Parameterize Group ID later
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "AshishConsumerGroupSnapshott");
        Consumer<String, byte[]> consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());

//        var sem = new Semaphore(0);
//        consumer.subscribe(List.of(SNAPSHOT_TOPIC));, new ConsumerRebalanceListener() {
//            @Override
//            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
//                System.out.println("Didn't expect the revoke!");
//            }
//
//            @Override
//            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
//                System.out.println("Partition assigned");
//                System.out.println("SEEKING");
//                collection.forEach(t -> consumer.seek(t, 0));
//                System.out.println("SEEK DONE");
//                sem.release();
//            }
//        });
//        System.out.println("first poll count: " + consumer.poll().count());
//        try {
//            sem.acquire();
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
//
//        System.out.println("Ready to consume at " + new Date());
        System.out.println("Snapshot Consumer successfully initialized");

            consumer.subscribe(List.of(SNAPSHOT_TOPIC));
            ConsumerRecords<String, byte[]> consumerRecords = consumer.poll(Duration.ofSeconds(1));
            consumerRecords.forEach(record -> {
                System.out.printf("offset = %d, key = %s, value = %s%n", record.offset(), record.key(), Arrays.toString(record.value()));
                Snapshot message = null;

                try {
                    System.out.println("Received snapshot from Replica: " + message.getReplicaId());
                    message = Snapshot.parseFrom(record.value());

                    System.out.println("Setting OPS lastSeenOffset to : " + message.getOperationsOffset());
                    lastSeenOperationsOffset = message.getOperationsOffset();

                    System.out.println("Setting lastSeenOrderingOffset to : " + message.getSnapshotOrderingOffset());
                    lastSeenOrderingOffset = message.getSnapshotOrderingOffset();

                    System.out.println("Syncing map with snapshot map  : " + message.getTableMap());
                    replicatedTable.sync(message.getTableMap());

                    System.out.println("Syncing Client Txn map with snapshot map  : " + message.getClientCountersMap());
                    for (String key : message.getClientCountersMap().keySet()) {
                        ClientTxnLog.put(key, message.getClientCountersMap().get(key));
                    }
                } catch (InvalidProtocolBufferException e) {
                    System.out.println("INVALID MESSAGE TYPE Received");
                    e.printStackTrace();
                }
            });

    }




//                    //this is take snapshot and publish
//                    //TODO Pull out name from SO topic
//                    System.out.println("Replica ID from Snapshot Ordering topic : " + message.getReplicaId());
//                    Snapshot snapshot = Snapshot.newBuilder()
//                            .setReplicaId(Replica.name)
//                            .putAllTable(replicatedTable.hashtable)
//                            .setOperationsOffset(lastSeenOffset)
//                            .putAllClientCounters(Replica.ClientTxnLog)
//                            //TODO GET FROM SO topic .setSnapshotOrderingOffset()
//                            .build();
//                    //publish snapshot
//                    sendMessage(snapshot.toByteArray(),SNAPSHOT_TOPIC);
//                    //join the Que again
//                    sendMessage(SnapshotOrdering.newBuilder().setReplicaId(name).build().toByteArray(),SNAPSHOT_ORDERING_TOPIC);
//                } catch (InvalidProtocolBufferException e) {
//                    System.out.println("INVALID MESSAGE TYPE Received");
//                    e.printStackTrace();
//                }
//                System.out.println("Consumed message from KafkaSnapshotConsumer: " + message);
//            });
//        }

    void sendMessage(byte[] message,String topic) {
        if (!Objects.isNull(producer)) {
            var record = new ProducerRecord<String, byte[]>(topic, message);
            producer.send(record);
            System.out.println("Publish to Topic: "+ topic );
        }
    }

}
