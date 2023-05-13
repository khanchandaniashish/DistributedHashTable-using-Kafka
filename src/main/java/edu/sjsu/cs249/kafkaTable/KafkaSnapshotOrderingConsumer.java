package edu.sjsu.cs249.kafkaTable;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;

import static edu.sjsu.cs249.kafkaTable.Replica.SNAPSHOT_ORDERING_TOPIC;

/**
 * @author ashish
 */
public class KafkaSnapshotOrderingConsumer {

    String bootstrapServer;
    ReplicatedTable replicatedTable;

    Long seekToOffset;

    Producer producer;

    KafkaSnapshotOrderingConsumer(String bootstrapServer, ReplicatedTable replicatedTable, Long seekToOffset, Producer operationsProducer) {
        this.bootstrapServer = bootstrapServer;
        this.replicatedTable = replicatedTable;
        this.seekToOffset = seekToOffset;
        this.producer = operationsProducer;
    }

//    @Override
    public void run() {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        //TODO: Parameterize Group ID later
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, "AshishConsumerGroupSnapshotOrdering");
        Consumer<String, byte[]> consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());

        //Seeks consumer to specified offset and inititialises listener from there
        initializeAndSeekConsumer(consumer,seekToOffset);
        ConsumerRecords<String, byte[]> consumerRecords = consumer.poll(Duration.ofSeconds(1));
        joinTheSnapshotOrdering(consumerRecords);
    }

    private void joinTheSnapshotOrdering(ConsumerRecords<String, byte[]> consumerRecords) {
        for (var consumerRecord : consumerRecords) {
            try {
                var message = SnapshotOrdering.parseFrom(consumerRecord.value());
                System.out.println("Consumed message from KafkaSnapshotOrderingConsumer: " + message);
                if (message.getReplicaId().equals(Replica.name)) {
                    System.out.println("Found myself already in SNAP ORDERING QUE. Skipping the publish to SO");
                    return;
                }
            } catch (InvalidProtocolBufferException e) {
                System.out.println("INVALID MESSAGE TYPE Received");
                e.printStackTrace();
            }
        }
        sendMessage(SnapshotOrdering.newBuilder().setReplicaId(Replica.name).build());
    }

    private  void initializeAndSeekConsumer(Consumer<String, byte[]> consumer,Long offsetToBeginFrom) {
        var sem = new Semaphore(0);
        consumer.subscribe(List.of(SNAPSHOT_ORDERING_TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
                System.out.println("Didn't expect the revoke!");
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
                System.out.println("Partition assigned");
                System.out.println("SEEKING");
                collection.forEach(t -> consumer.seek(t, offsetToBeginFrom));
                System.out.println("SEEK DONE");
                sem.release();
            }
        });
        System.out.println("first poll count: " + consumer.poll(0).count());

        try {
            sem.acquire();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("Ready to consume at " + new Date());
        System.out.println("Consumer successfully initialized");
    }

    void sendMessage(SnapshotOrdering message){
        if (!Objects.isNull(producer)) {
            var record = new ProducerRecord<String, byte[]>(SNAPSHOT_ORDERING_TOPIC, message.toByteArray());
            producer.send(record);
            System.out.println("Publish to SNAPSHOT_ORDERING_TOPIC and joined the que as : "+ Replica.name);
        }
    }


}
