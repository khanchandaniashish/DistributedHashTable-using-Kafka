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

import static edu.sjsu.cs249.kafkaTable.Replica.*;

/**
 * @author ashish
 */
public class KafkaSnapshotOrderingConsumer {

    String bootstrapServer;
    ReplicatedTable replicatedTable;
    Producer producer;

    Consumer<String, byte[]> consumer;

    Consumer<String, byte[]> secondConsumerConfig;


    KafkaSnapshotOrderingConsumer(String bootstrapServer, ReplicatedTable replicatedTable, Producer operationsProducer) {
        this.bootstrapServer = bootstrapServer;
        this.replicatedTable = replicatedTable;
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
        consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
        properties.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG,"1");
        secondConsumerConfig = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
        //Seeks consumer to specified offset and inititialises listener from there
        System.out.println("lastSeenOrderingOffset is :"+lastSeenOrderingOffset);
        initializeAndSeekConsumer(consumer,lastSeenOrderingOffset+1);
        ConsumerRecords<String, byte[]> consumerRecords = consumer.poll(Duration.ofSeconds(1));
        joinTheSnapshotOrdering(consumerRecords);
    }

    private void joinTheSnapshotOrdering(ConsumerRecords<String, byte[]> consumerRecords) {
        for (var consumerRecord : consumerRecords) {
            try {
                var message = SnapshotOrdering.parseFrom(consumerRecord.value());
                System.out.println("Consumed message from KafkaSnapshotOrderingConsumer: " + message);
                //TODO Case where you are already in the Q.. where to read from?
                if (message.getReplicaId().equals(Replica.name)) {
                    System.out.println("Found myself already in SNAP ORDERING QUE. Skipping the publish to SO");
                    resetOffsetToBeginning();
                    return;
                }
            } catch (InvalidProtocolBufferException e) {
                System.out.println("INVALID MESSAGE TYPE Received");
                e.printStackTrace();
            }
        }
        //Publish message to join the Q
        sendMessage(SnapshotOrdering.newBuilder().setReplicaId(Replica.name).build());
        resetOffsetToBeginning();
    }

    private void resetOffsetToBeginning() {
        //Reset the offset to zero
        System.out.println("Resetting Ordering Snapshot to beginning");
        consumer.unsubscribe();
        initializeAndSeekConsumer(secondConsumerConfig,lastSeenOperationsOffset);
//        Collection<TopicPartition> collection = consumer.assignment();
//        collection.forEach(t -> consumer.seek(t, 0));
    }

    public boolean isTimeToPublishSnapshot(){
        ConsumerRecords<String, byte[]> consumerRecords = secondConsumerConfig.poll(Duration.ofSeconds(1));
        System.out.println("polling for 1 seconds");
        //TODO HERE
        System.out.println("Consumer records count is  "+ consumerRecords.count());
        for (var consumerRecord : consumerRecords) {
            try {
                var message = SnapshotOrdering.parseFrom(consumerRecord.value());
                System.out.println("Consumed message from KafkaSnapshotOrderingConsumer: " + message);
                System.out.println("Consumed message from KafkaSnapshotOrderingConsumer with replID: " + message.getReplicaId());
                if (message.getReplicaId().equals(Replica.name)) {
                    System.out.println("Found myself next in SNAP ORDERING QUE. I gotta publish the snapshot");
                    return true;
                }
            } catch (InvalidProtocolBufferException e) {
                System.out.println("INVALID MESSAGE TYPE Received");
                e.printStackTrace();
            }
        }
        return false;
    }

    private  void initializeAndSeekConsumer(Consumer<String, byte[]> passedConsumer,Long offsetToBeginFrom) {
        System.out.println("Called initializeAndSeekConsumer with offsetToBeginFrom: "+offsetToBeginFrom);
        var sem = new Semaphore(0);

        passedConsumer.subscribe(List.of(SNAPSHOT_ORDERING_TOPIC), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
                System.out.println("Didn't expect the revoke!");
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
                System.out.println("Partition assigned");
                System.out.println("SEEKING");
                collection.forEach(t -> passedConsumer.seek(t, offsetToBeginFrom+1));
                System.out.println("SEEK DONE");
                sem.release();
            }
        });
        System.out.println("first poll count: " + passedConsumer.poll(offsetToBeginFrom+1).count());
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
