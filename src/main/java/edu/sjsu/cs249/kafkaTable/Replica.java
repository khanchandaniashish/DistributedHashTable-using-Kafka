package edu.sjsu.cs249.kafkaTable;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.Properties;

/**
 * @author ashish
 */
public class Replica {
    public final String bootstrapServer;
    public static String name;
    private final int port;
    public static int snapshotDecider;
    public ReplicatedTable replicatedTable;

    public static Long lastSeenOperationsOffset;

    public static Long lastSeenOrderingOffset;

    public static String OPERATIONS_TOPIC;
    public static String SNAPSHOT_TOPIC;
    public static String SNAPSHOT_ORDERING_TOPIC;

    String topicPrefix;
    Producer kafkaProducer;
    KafkaOperationsConsumer operationsConsumer;

    static HashMap<String, Integer> ClientTxnLog;

    HashMap<ClientXid, StreamObserver<IncResponse>> incResponseHashMap;

    KafkaSnapshotOrderingConsumer kafkaSnapshotOrderingConsumer;

    KafkaSnapshotConsumer kafkaSnapshotConsumer;


    public Replica(String server, String name, int port, int snapshotDecider, String topicPrefix) {
        ClientTxnLog = new HashMap<>();
        incResponseHashMap = new HashMap<>();
        this.bootstrapServer = server;
        this.name = name;
        this.port = port;
        this.snapshotDecider = snapshotDecider;
        this.replicatedTable = new ReplicatedTable();
        this.topicPrefix = topicPrefix;
        OPERATIONS_TOPIC = topicPrefix + "operations";
        SNAPSHOT_TOPIC = topicPrefix + "snapshot";
        SNAPSHOT_ORDERING_TOPIC = topicPrefix + "snapshotOrdering";

        System.out.println("OPERATIONS_TOPIC :" + OPERATIONS_TOPIC);
        System.out.println("SNAPSHOT_TOPIC :" + SNAPSHOT_TOPIC);
        System.out.println("SNAPSHOT_ORDERING_TOPIC :" + SNAPSHOT_ORDERING_TOPIC);

        initOperationsProducer();
        kafkaSnapshotConsumer = new KafkaSnapshotConsumer(bootstrapServer, replicatedTable, 0L, kafkaProducer);
        kafkaSnapshotConsumer.run();
        kafkaSnapshotOrderingConsumer = new KafkaSnapshotOrderingConsumer(bootstrapServer, replicatedTable, lastSeenOrderingOffset, kafkaProducer);
        kafkaSnapshotOrderingConsumer.run();
        System.out.println("STARTINGGG OPSSSS");
        operationsConsumer = new KafkaOperationsConsumer(bootstrapServer, replicatedTable, incResponseHashMap);
        operationsConsumer.run();

    }

    private void initOperationsProducer() {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServer);
        kafkaProducer = new KafkaProducer<>(properties, new StringSerializer(), new ByteArraySerializer());
    }

    public void letsGo() throws IOException, InterruptedException {
        System.out.println("Starting up Server");
        Server server = ServerBuilder.forPort(port)
                .addService(new KafkaTableGrpcService(this, replicatedTable))
                .addService(new KafkaTableDebugGrpcService(this))
                .build();
        server.start();
        System.out.println("Server listening on port: " + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.shutdown();
            System.out.println("Successfully stopped the server");
        }));
        server.awaitTermination();
    }

    void sendMessage(String topicName, PublishedItem message) {
        if (!Objects.isNull(kafkaProducer)) {
            var record = new ProducerRecord<String, byte[]>(topicName, message.toByteArray());
            kafkaProducer.send(record);
        }
    }
}
