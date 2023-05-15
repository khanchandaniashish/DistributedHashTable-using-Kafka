package edu.sjsu.cs249.kafkaTable;

import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Command
public class Main {
    static {
        // quiet some kafka messages
        //System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
    }

    @Command
    int publish(@Parameters(paramLabel = "kafkaHost:port") String server,
                @Parameters(paramLabel = "topic-name") String name) throws IOException {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, server);
        var producer = new KafkaProducer<>(properties, new StringSerializer(), new ByteArraySerializer());
        var br = new BufferedReader(new InputStreamReader(System.in));
        for (int i = 0;; i++) {
            var line = br.readLine();
            if (line == null) break;
            var bytes = SimpleMessage.newBuilder()
                    .setMessage(line)
                    .build().toByteArray();
            var record = new ProducerRecord<String, byte[]>(name, bytes);
            producer.send(record);
        }
        return 0;
    }

    @Command
    int consume(@Parameters(paramLabel = "topic-name") String name,
                @Parameters(paramLabel = "group-id") String id)
            throws InvalidProtocolBufferException, InterruptedException {
        var properties = new Properties();
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, id);
        properties.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "10000");
        var consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new ByteArrayDeserializer());
        System.out.println("Starting at " + new Date());
        var sem = new Semaphore(0);
        consumer.subscribe(List.of(name), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
                System.out.println("Didn't expect the revoke!");
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
                System.out.println("Partition assigned");
                collection.stream().forEach(t -> consumer.seek(t, 0));
                sem.release();
            }
        });
        System.out.println("first poll count: " + consumer.poll(0).count());
        sem.acquire();
        System.out.println("Ready to consume at " + new Date());
        while (true) {
            var records = consumer.poll(Duration.ofSeconds(20));
            System.out.println("Got: " + records.count());
            for (var record: records) {
                System.out.println(record.headers());
                System.out.println(record.timestamp());
                System.out.println(record.timestampType());
                System.out.println(record.offset());
                var message = SimpleMessage.parseFrom(record.value());
                System.out.println(message);
            }
        }
    }

    @Command
    int listTopics(@Parameters(paramLabel = "kafkaHost:port") String server) throws ExecutionException, InterruptedException {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, server);
        try (var admin = Admin.create(properties)) {
            var rc = admin.listTopics();
            var listings = rc.listings().get();
            for (var l : listings) {
                System.out.println(l);
            }
        }
        return 0;
    }

    @Command
    int createTopic(@Parameters(paramLabel = "kafkaHost:port") String server,
                    @Parameters(paramLabel = "topic-name") String name) throws InterruptedException, ExecutionException {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, server);
        try (var admin = Admin.create(properties)) {
            var rc = admin.createTopics(List.of(new NewTopic(name, 1, (short) 1)));
            rc.all().get();
        }
        return 0;
    }

    @Command(description = "delete the operations, snapshotOrder, and snapshot topics for a given prefix")
    int deleteTableTopics(@Parameters(paramLabel = "kafkaHost:port") String server,
                          @Parameters(paramLabel = "prefix") String prefix) throws ExecutionException, InterruptedException {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, server);
        try (var admin = Admin.create(properties)) {
            List<String> topics = List.of(
                    prefix + "operations",
                    prefix + "snapshot",
                    prefix + "snapshotOrdering"
            );
            admin.deleteTopics(topics);
            System.out.println("deleted topics: " + Arrays.toString(topics.toArray()));
        }
        return 0;
    }
    @Command(description = "create the operations, snapshotOrder, and snapshot topics for a given prefix")
    int createTableTopics(@Parameters(paramLabel = "kafkaHost:port") String server,
                          @Parameters(paramLabel = "prefix") String prefix) throws ExecutionException, InterruptedException {
        var properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, server);
        try (var admin = Admin.create(properties)) {
            var rc = admin.createTopics(List.of(
                    new NewTopic(prefix + "operations", 1, (short) 1),
                    new NewTopic(prefix + "snapshot", 1, (short) 1),
                    new NewTopic(prefix + "snapshotOrdering", 1, (short) 1)
                    ));
            rc.all().get();
        }
        var producer = new KafkaProducer<>(properties, new StringSerializer(), new ByteArraySerializer());
        var result = producer.send(new ProducerRecord<>(prefix + "snapshot", Snapshot.newBuilder()
                .setReplicaId("initializer")
                .setOperationsOffset(-1)
                .setSnapshotOrderingOffset(-1)
                .putAllTable(Map.of())
                .putAllClientCounters(Map.of())
                .build().toByteArray()));
        result.get();
        return 0;

    }

    private HashMap<String, ManagedChannel> getChannelsAndResetValues(String[] servers, int serverIndex, String id, String TESTING_KEY) throws InterruptedException {
        HashMap<String, ManagedChannel> channelHashMap = new HashMap();
        String[] var6 = servers;
        int var7 = servers.length;

        for(int var8 = 0; var8 < var7; ++var8) {
            String server = var6[var8];
            int lastColon = server.lastIndexOf(58);
            ManagedChannel channel = ManagedChannelBuilder.forAddress(server.substring(0, lastColon), Integer.parseInt(server.substring(lastColon + 1))).usePlaintext().build();
            channelHashMap.put(server, channel);
        }

        System.out.println("Getting snapshot values from " + servers[serverIndex]);
        KafkaTableDebugGrpc.KafkaTableDebugBlockingStub stub = KafkaTableDebugGrpc.newBlockingStub((Channel)channelHashMap.get(servers[serverIndex]));
        KafkaTableDebugResponse response = stub.debug(KafkaTableDebugRequest.newBuilder().build());
        Snapshot snapshot = response.getSnapshot();
        int lastClientCounter = snapshot.getClientCountersOrDefault(id, -1);
        if (snapshot.getTableMap().containsKey(TESTING_KEY) && (Integer)snapshot.getTableMap().get(TESTING_KEY) > 0) {
            System.out.println("RESETTING " + TESTING_KEY);
            KafkaTableGrpc.KafkaTableBlockingStub var10000 = KafkaTableGrpc.newBlockingStub((Channel)channelHashMap.get(servers[serverIndex]));
            IncRequest.Builder var10001 = IncRequest.newBuilder().setKey(TESTING_KEY).setIncValue(-(Integer)snapshot.getTableMap().get(TESTING_KEY));
            ClientXid.Builder var10002 = ClientXid.newBuilder().setClientid(id);
            ++lastClientCounter;
            var10000.inc(var10001.setXid(var10002.setCounter(lastClientCounter).build()).build());
            Thread.sleep(1000L);
            response = stub.debug(KafkaTableDebugRequest.newBuilder().build());
            snapshot = response.getSnapshot();
            if (snapshot.getTableMap().containsKey(TESTING_KEY) && (Integer)snapshot.getTableMap().get(TESTING_KEY) > 0) {
                System.out.println("RESETTING `" + TESTING_KEY + "` DID NOT WORK FOR " + servers[serverIndex]);
                return null;
            }
        }

        return channelHashMap;
    }


    @Command
    int basicTest(@Parameters(paramLabel = "clientId") String id, @Parameters(paramLabel = "init index to debug") int serverIndex, @Parameters(paramLabel = "grpclearcHost:port",arity = "1..*") String[] servers) throws InterruptedException {
        String TESTING_KEY = "testing_key";
        int TESTING_INCREMENT = 1;
        int INCREMENTS = 20;
        int currVal = 0;
        HashMap<String, ManagedChannel> channelHashMap = this.getChannelsAndResetValues(servers, serverIndex, id, TESTING_KEY);
        KafkaTableDebugGrpc.KafkaTableDebugBlockingStub stub = KafkaTableDebugGrpc.newBlockingStub((Channel)channelHashMap.get(servers[serverIndex]));
        KafkaTableDebugResponse response = stub.debug(KafkaTableDebugRequest.newBuilder().build());
        Snapshot snapshot = response.getSnapshot();
        int lastClientCounter = snapshot.getClientCountersOrDefault(id, -1);
        String[] var13 = servers;
        int var14 = servers.length;

        for(int var15 = 0; var15 < var14; ++var15) {
            String server = var13[var15];
            System.out.println("INC REQUESTS FOR " + server);

            for(int i = 0; i < INCREMENTS; ++i) {
                KafkaTableGrpc.KafkaTableBlockingStub var10000 = KafkaTableGrpc.newBlockingStub((Channel)channelHashMap.get(server));
                IncRequest.Builder var10001 = IncRequest.newBuilder().setKey(TESTING_KEY).setIncValue(TESTING_INCREMENT);
                ClientXid.Builder var10002 = ClientXid.newBuilder().setClientid(id);
                ++lastClientCounter;
                var10000.inc(var10001.setXid(var10002.setCounter(lastClientCounter).build()).build());
                System.out.println(i + 1 + "/" + INCREMENTS + " REQUESTS DONE");
            }

            currVal += 20;
            System.out.println("REQUESTS COMPLETED, VERIFYING GET FOR SERVERS");
            Thread.sleep(1000L);
            if (this.verifyGet(servers, channelHashMap, TESTING_KEY, id, lastClientCounter, currVal) == -1) {
                return -1;
            }

            response = stub.debug(KafkaTableDebugRequest.newBuilder().build());
            snapshot = response.getSnapshot();
            lastClientCounter = snapshot.getClientCountersOrDefault(id, -1);
            Thread.sleep(1000L);
            if (this.verifyingSnapshots(servers, channelHashMap, TESTING_KEY, id) == -1) {
                return -1;
            }
        }

        System.out.println("ALL TESTS PASSED! CHECK SNAPSHOTS FOR SYNC");
        return 0;
    }

    private int verifyGet(String[] servers, HashMap<String, ManagedChannel> channelHashMap, String TESTING_KEY, String id, int lastClientCounter, int expectedValue) {
        System.out.println("VERIFYING GET");
        String[] var7 = servers;
        int var8 = servers.length;

        for(int var9 = 0; var9 < var8; ++var9) {
            String server = var7[var9];
            KafkaTableGrpc.KafkaTableBlockingStub var10000 = KafkaTableGrpc.newBlockingStub((Channel)channelHashMap.get(server));
            GetRequest.Builder var10001 = GetRequest.newBuilder().setKey(TESTING_KEY);
            ClientXid.Builder var10002 = ClientXid.newBuilder().setClientid(id);
            ++lastClientCounter;
            int value = var10000.get(var10001.setXid(var10002.setCounter(lastClientCounter).build()).build()).getValue();
            if (value != expectedValue) {
                System.out.println("get for " + TESTING_KEY + " for " + server + " is incorrect");
                System.out.println("returned: " + value + "; expected: " + expectedValue);
                return -1;
            }
        }

        System.out.println("GET VERIFIED FOR ALL SERVERS");
        return 1;
    }

    private int verifyingSnapshots(String[] servers, HashMap<String, ManagedChannel> channelHashMap, String TESTING_KEY, String id) {
        ArrayList<Snapshot> snapshots = new ArrayList();
        String[] var6 = servers;
        int var7 = servers.length;

        for(int var8 = 0; var8 < var7; ++var8) {
            String debugReqServer = var6[var8];
            Snapshot currSnapShot = KafkaTableDebugGrpc.newBlockingStub((Channel)channelHashMap.get(debugReqServer)).debug(KafkaTableDebugRequest.newBuilder().build()).getSnapshot();
            snapshots.add(currSnapShot);
        }

        System.out.println("VERIFYING SNAPSHOTS");

        for(int i = 1; i < snapshots.size(); ++i) {
            if (((Snapshot)snapshots.get(i)).getOperationsOffset() != ((Snapshot)snapshots.get(i - 1)).getOperationsOffset()) {
                System.out.println("snapshots operations offsets don't match");
                return -1;
            }

            if (((Snapshot)snapshots.get(i)).getSnapshotOrderingOffset() != ((Snapshot)snapshots.get(i - 1)).getSnapshotOrderingOffset()) {
                System.out.println("snapshots snapshot-ordering offsets don't match");
                return -1;
            }

            if (!Objects.equals(((Snapshot)snapshots.get(i)).getTableMap().get(TESTING_KEY), ((Snapshot)snapshots.get(i - 1)).getTableMap().get(TESTING_KEY))) {
                System.out.println("snapshots key-value pair don't match");
                return -1;
            }

            if (!Objects.equals(((Snapshot)snapshots.get(i)).getClientCountersMap().get(id), ((Snapshot)snapshots.get(i - 1)).getClientCountersMap().get(id))) {
                System.out.println("snapshots client counters don't match");
                return -1;
            }
        }

        System.out.println("SNAPSHOTS VERIFIED");
        return 1;
    }


    @Command
    int get(@Parameters(paramLabel = "key") String key,
            @Parameters(paramLabel = "clientId") String id,
            @Parameters(paramLabel = "grpcHost:port") String server) {
        var clientXid = ClientXid.newBuilder().setClientid(id).setCounter((int)(System.currentTimeMillis()/1000)).build();
        var stub = KafkaTableGrpc.newBlockingStub(ManagedChannelBuilder.forTarget(server).usePlaintext().build());
        var rsp = stub.get(GetRequest.newBuilder().setKey(key).setXid(clientXid).build());
        System.out.println(rsp.getValue());
        return 0;
    }

        @Command
    int inc(@Parameters(paramLabel = "key") String key,
            @Parameters(paramLabel = "amount") int amount,
            @Parameters(paramLabel = "clientId") String id,
            @Option(names = "--repeat") boolean repeat,
            @Option(names = "--concurrent") boolean concurrent,
            @Parameters(paramLabel = "grpcHost:port", arity = "1..*") String[] servers) {
        int count = repeat ? 2 : 1;
        var clientXid = ClientXid.newBuilder().setClientid(id).setCounter((int)(System.currentTimeMillis()/1000)).build();
        System.out.println(clientXid);
        for (int i = 0; i < count; i++) {
            var s = Arrays.stream(servers);
            if (concurrent) s = s.parallel();
            var result = s.map(server -> {
                var stub = KafkaTableGrpc.newBlockingStub(ManagedChannelBuilder.forTarget(server).usePlaintext().build());
                try {
                    stub.inc(IncRequest.newBuilder().setKey(key).setIncValue(amount).setXid(clientXid).build());
                    return server + ": success";
                } catch (Exception e) {
                    return server + ": " + e.getMessage();
                }
            }).collect(Collectors.joining(", "));
            System.out.println(result);
        }
        return 0;
    }

    @Command
    int replica(@Parameters(paramLabel = "kafkaHost:port") String server,
                @Parameters(paramLabel = "name") String name,
                @Parameters(paramLabel = "port") int port,
                @Parameters(paramLabel = "snapshotDecider") int snapshotDecider,
                @Parameters(paramLabel = "topicPrefix") String topicPrefix) throws IOException, InterruptedException {
        new Replica(server, name, port, snapshotDecider, topicPrefix).letsGo();
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Command
    int basicDupTest(@Parameters(paramLabel = "clientId") String id, @Parameters(paramLabel = "init index to debug") int serverIndex, @Parameters(paramLabel = "grpclearcHost:port",arity = "1..*") String[] servers) throws InterruptedException {
        String TESTING_KEY = "testing_key";
        int TESTING_INCREMENT = 1;
        int INCREMENTS = 20;
        HashMap<String, ManagedChannel> channelHashMap = this.getChannelsAndResetValues(servers, serverIndex, id, TESTING_KEY);
        KafkaTableDebugGrpc.KafkaTableDebugBlockingStub stub = KafkaTableDebugGrpc.newBlockingStub((Channel)channelHashMap.get(servers[serverIndex]));
        KafkaTableDebugResponse response = stub.debug(KafkaTableDebugRequest.newBuilder().build());
        Snapshot snapshot = response.getSnapshot();
        int lastClientCounter = snapshot.getClientCountersOrDefault(id, -1);

        for(int i = 0; i < INCREMENTS; ++i) {
            ++lastClientCounter;
            int counter = lastClientCounter;
            final CountDownLatch countDownLatch = new CountDownLatch(servers.length);
            String[] var15 = servers;
            int var16 = servers.length;

            for(int var17 = 0; var17 < var16; ++var17) {
                String server = var15[var17];
                KafkaTableGrpc.newStub((Channel)channelHashMap.get(server)).inc(IncRequest.newBuilder().setKey(TESTING_KEY).setIncValue(TESTING_INCREMENT).setXid(ClientXid.newBuilder().setClientid(id).setCounter(counter).build()).build(), new StreamObserver<IncResponse>() {
                    public void onNext(IncResponse incResponse) {
                    }

                    public void onError(Throwable throwable) {
                    }

                    public void onCompleted() {
                        countDownLatch.countDown();
                    }
                });
            }

            countDownLatch.await();
            System.out.println(i + 1 + "/" + INCREMENTS + " INCREMENTS DONE");
        }

        System.out.println("REQUESTS COMPLETED");
        Thread.sleep(1000L);
        if (this.verifyGet(servers, channelHashMap, TESTING_KEY, id, lastClientCounter, 20) == -1) {
            return -1;
        } else {
            Thread.sleep(1000L);
            if (this.verifyingSnapshots(servers, channelHashMap, TESTING_KEY, id) == -1) {
                return -1;
            } else {
                System.out.println("TEST PASSED!");
                return 0;
            }
        }
    }

    @Command
    int recoverTests(@Parameters(paramLabel = "clientId") String id, @Parameters(paramLabel = "init index to debug") int serverIndex, @Parameters(paramLabel = "grpclearcHost:port",arity = "1..*") String[] servers) throws InterruptedException {
        String TESTING_KEY = "testing_key";
        int TESTING_INCREMENT = 1;
        AtomicInteger lastClientCounter = new AtomicInteger();
        AtomicBoolean isRunning = new AtomicBoolean(true);
        HashMap<String, ManagedChannel> channelHashMap = this.getChannelsAndResetValues(servers, serverIndex, id, TESTING_KEY);
        KafkaTableDebugGrpc.KafkaTableDebugBlockingStub stub = KafkaTableDebugGrpc.newBlockingStub((Channel)channelHashMap.get(servers[serverIndex]));
        KafkaTableDebugResponse response = stub.debug(KafkaTableDebugRequest.newBuilder().build());
        Snapshot snapshot = response.getSnapshot();
        lastClientCounter.set(snapshot.getClientCountersOrDefault(id, -1));
        AtomicInteger requestCount = new AtomicInteger(0);
        Thread incrementThread = new Thread(() -> {
            System.out.println("STARTED REQUESTS");

            while(isRunning.get()) {
                String[] var8 = servers;
                int var9 = servers.length;

                for(int var10 = 0; var10 < var9; ++var10) {
                    String server = var8[var10];
                    if (isRunning.get()) {
                        try {
                            KafkaTableGrpc.newBlockingStub((Channel)channelHashMap.get(server)).inc(IncRequest.newBuilder().setKey(TESTING_KEY).setIncValue(TESTING_INCREMENT).setXid(ClientXid.newBuilder().setClientid(id).setCounter(lastClientCounter.incrementAndGet()).build()).build());
                            requestCount.incrementAndGet();
                        } catch (Exception var13) {
                            System.out.println(var13);
                        }
                    }
                }

                try {
                    Thread.sleep(100L);
                } catch (InterruptedException var14) {
                    throw new RuntimeException(var14);
                }
            }

        });
        incrementThread.start();
        Scanner scanner = new Scanner(System.in);

        while(true) {
            String str = scanner.next();
            if (Objects.equals(str, "exit")) {
                isRunning.set(false);
                Thread.sleep(5000L);
                if (this.verifyGet(servers, channelHashMap, TESTING_KEY, id, lastClientCounter.incrementAndGet(), requestCount.get()) == -1) {
                    return -1;
                }

                Thread.sleep(5000L);
                this.verifyingSnapshots(servers, channelHashMap, TESTING_KEY, id);
                return 0;
            }

            if (Objects.equals(str, "status")) {
                System.out.println(requestCount.get() + " REQUESTS DONE");
            } else if (str.matches("172\\.27\\.24\\.[0-9]+:[0-9]+")) {
                try {
                    KafkaTableDebugGrpc.newBlockingStub((Channel)channelHashMap.get(str)).exit(ExitRequest.newBuilder().build());
                    System.out.println(str + " EXITED");
                } catch (Exception var17) {
                    System.out.println("EXIT EXCEPTION " + var17);
                }
            }

            Thread.sleep(100L);
        }
    }

    @Command
    int snapshotRecoverTest(@Parameters(paramLabel = "kafkaHost:port") String server, @Parameters(paramLabel = "clientId") String id, @Parameters(paramLabel = "prefix") String prefix, @Parameters(paramLabel = "snapshot cycle value") int snapshotCycle, @Parameters(paramLabel = "init index to debug") int serverIndex, @Parameters(paramLabel = "grpclearcHost:port",arity = "1..*") String[] servers) throws InterruptedException, InvalidProtocolBufferException {
        String TESTING_KEY = "testing_key";
        int TESTING_INCREMENT = 1;
        HashMap<String, ManagedChannel> channelHashMap = this.getChannelsAndResetValues(servers, serverIndex, id, TESTING_KEY);
        KafkaTableDebugGrpc.KafkaTableDebugBlockingStub stub = KafkaTableDebugGrpc.newBlockingStub((Channel)channelHashMap.get(servers[serverIndex]));
        KafkaTableDebugResponse response = stub.debug(KafkaTableDebugRequest.newBuilder().build());
        Snapshot snapshot = response.getSnapshot();
        int lastClientCounter = snapshot.getClientCountersOrDefault(id, -1);
        Properties properties = new Properties();
        properties.put("bootstrap.servers", server);
        properties.setProperty("auto.offset.reset", "earliest");
        properties.setProperty("group.id", id + "-snapshotOrdering");
        properties.setProperty("enable.auto.commit", "false");
        properties.setProperty("session.timeout.ms", "10000");
        final KafkaConsumer<String, byte[]> consumerSnapshotOrdering = new KafkaConsumer(properties, new StringDeserializer(), new ByteArrayDeserializer());
        PrintStream var10000 = System.out;
        Date var10001 = new Date();
        var10000.println("Starting at " + var10001);
        final Semaphore sem = new Semaphore(0);
        final AtomicReference<TopicPartition> snapshotOrderingPartition = new AtomicReference();
        consumerSnapshotOrdering.subscribe(List.of(prefix + "snapshotOrdering"), new ConsumerRebalanceListener() {
            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
                System.out.println("Didn't expect the revoke!");
            }

            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
                System.out.println("Partition assigned");
                collection.stream().forEach((t) -> {
                    snapshotOrderingPartition.set(t);
                    consumerSnapshotOrdering.seek(t, 0L);
                });
                sem.release();
            }
        });
        System.out.println("first poll count: " + consumerSnapshotOrdering.poll(0L).count());
        sem.acquire();
        var10000 = System.out;
        var10001 = new Date();
        var10000.println("Ready to consume at " + var10001);
        ConsumerRecords<String, byte[]> records = consumerSnapshotOrdering.poll(Duration.ofSeconds(5L));
        ArrayList<SnapshotOrdering> snapshotOrderings = new ArrayList();
        long lastOffset = -1L;

        ConsumerRecord record;
        for(Iterator var22 = records.iterator(); var22.hasNext(); lastOffset = record.offset()) {
            record = (ConsumerRecord)var22.next();
            SnapshotOrdering snapshotOrdering = SnapshotOrdering.parseFrom((byte[])record.value());
            snapshotOrderings.add(snapshotOrdering);
        }

        System.out.println("STOP AND RESTART REPLICAS! Press any key to continue...");
        Scanner scanner = new Scanner(System.in);
        scanner.next();
        long offsetToStart = lastOffset - (long)servers.length + 1L;
        consumerSnapshotOrdering.seek((TopicPartition)snapshotOrderingPartition.get(), offsetToStart);
        consumerSnapshotOrdering.poll(Duration.ZERO);
        records = consumerSnapshotOrdering.poll(Duration.ofSeconds(5L));
        long i = offsetToStart;

        for(Iterator var27 = records.iterator(); var27.hasNext(); ++i) {
            record = (ConsumerRecord)var27.next();
            SnapshotOrdering snapshotOrdering = SnapshotOrdering.parseFrom((byte[])record.value());
            if (!((SnapshotOrdering)snapshotOrderings.get((int)i)).getReplicaId().equals(snapshotOrdering.getReplicaId())) {
                System.out.println("Order does not match at " + i + " index");
                return -1;
            }
        }

        System.out.println("SNAPSHOT ORDERING VERIFIED!");
        System.out.println("DOING INC REQUESTS TO MAKE EVERYONE SNAPSHOT!");
        int numberOfRequests = snapshotCycle * servers.length;
        System.out.println("DOING " + numberOfRequests + " REQUESTS");

        while(numberOfRequests > 0) {
            KafkaTableGrpc.KafkaTableBlockingStub var34 = KafkaTableGrpc.newBlockingStub((Channel)channelHashMap.get(servers[numberOfRequests % servers.length]));
            IncRequest.Builder var35 = IncRequest.newBuilder().setKey(TESTING_KEY).setIncValue(TESTING_INCREMENT);
            ClientXid.Builder var10002 = ClientXid.newBuilder().setClientid(id);
            ++lastClientCounter;
            var34.inc(var35.setXid(var10002.setCounter(lastClientCounter).build()).build());
            System.out.println("lastClientCounter: " + lastClientCounter);
            --numberOfRequests;
            System.out.println("" + numberOfRequests + " remaining");
        }

        System.out.println("INC REQUESTS DONE! VERIFYING THE SNAPSHOT ORDER");
        System.out.println(snapshotOrderings.subList((int)offsetToStart, (int)(lastOffset + 1L)));
        Thread.sleep(1000L);
        properties.setProperty("group.id", id + "-snapshot");
        final KafkaConsumer<String, byte[]> consumerSnapshot = new KafkaConsumer(properties, new StringDeserializer(), new ByteArrayDeserializer());
        var10000 = System.out;
        var10001 = new Date();
        var10000.println("Starting at " + var10001);
        final AtomicReference<TopicPartition> snapshotPartition = new AtomicReference();
        consumerSnapshot.subscribe(List.of(prefix + "snapshot"), new ConsumerRebalanceListener() {
            public void onPartitionsRevoked(Collection<TopicPartition> collection) {
                System.out.println("Didn't expect the revoke!");
            }

            public void onPartitionsAssigned(Collection<TopicPartition> collection) {
                System.out.println("Partition assigned");
                collection.stream().forEach((t) -> {
                    snapshotPartition.set(t);
                    consumerSnapshot.seek(t, 0L);
                });
                sem.release();
            }
        });
        System.out.println("first poll count: " + consumerSnapshot.poll(0L).count());
        sem.acquire();
        var10000 = System.out;
        var10001 = new Date();
        var10000.println("Ready to consume at " + var10001);
        records = consumerSnapshot.poll(Duration.ofSeconds(5L));
        ArrayList<Snapshot> snapshots = new ArrayList();
        int lastSnapshotOffset = 0;

        Iterator var32;

        for(var32 = records.iterator(); var32.hasNext(); lastSnapshotOffset = (int)record.offset()) {
            record = (ConsumerRecord)var32.next();
            snapshots.add(Snapshot.parseFrom((byte[])record.value()));
        }

        i = offsetToStart;
        consumerSnapshot.seek((TopicPartition)snapshotPartition.get(), (long)(lastSnapshotOffset - servers.length + 1));
        consumerSnapshot.poll(Duration.ZERO);
        records = consumerSnapshot.poll(Duration.ofSeconds(5L));

        for(var32 = records.iterator(); var32.hasNext(); ++i) {
            record = (ConsumerRecord)var32.next();
            snapshot = Snapshot.parseFrom((byte[])record.value());
            if (!snapshot.getReplicaId().equals(((SnapshotOrdering)snapshotOrderings.get((int)i)).getReplicaId())) {
                var10000 = System.out;
                long var36 = record.offset();
                var10000.println("Snapshot at " + var36 + " offset does not match snapshot ordering at " + i + " offset");
                return -1;
            }
        }

        if (this.verifyingSnapshots(servers, channelHashMap, TESTING_KEY, id) == -1) {
            return -1;
        } else {
            System.out.println("TEST PASSED!");
            return 0;
        }
    }
}