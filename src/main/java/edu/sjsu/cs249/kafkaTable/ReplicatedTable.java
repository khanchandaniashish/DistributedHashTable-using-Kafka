package edu.sjsu.cs249.kafkaTable;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ashish
 */
public class ReplicatedTable {

    HashMap<String, Integer> hashtable;

    ReplicatedTable() {
        hashtable = new HashMap<>();
    }

    Integer get(String key) {
        return hashtable.get(key);
    }

    void inc(String key, int value) {
        int updatedValue = hashtable.getOrDefault(key, 0) + value;
        System.out.println("New value :" + updatedValue);
        if(updatedValue >= 0) {
            hashtable.put(key, updatedValue);
            System.out.println("Updated key: " + key + " with  updatedValue: " + updatedValue);
        }
        else{
            System.out.println("INC NOT APPLIED as inc was going below zero. Skipped key: " + key + " with  value: " + value);
        }
    }

    @Override
    public String toString() {
        hashtable.forEach((key, value) -> System.out.println(key + " " + value));
        return "ReplicatedTable{" +
                "hashtable=" + hashtable +
                '}';
    }

    public void sync(Map<String, Integer> SnapshotMap) {
        System.out.println("Pre Sync map: " + hashtable.toString());
        hashtable.clear();
        for (String key : SnapshotMap.keySet()) {
            hashtable.put(key, SnapshotMap.get(key));
        }
        System.out.println("Synced map with Snap Map : " + hashtable.toString());
    }
}
