package cn.zbx1425.mtrsteamloco.data;

import java.util.*;
import java.util.function.Function;

import mtr.mappings.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public class Tree<T> {

    public static abstract class Node<T> extends Tree<T> {
        public final String key;
        public MutableComponent name;
        public final Node<T> parent;
        
        public Node(String key, String name, Node<T> parent) {
            this.key = key;
            this.name = Text.translatable(name);
            this.parent = parent;
        }

        protected void dealWithDuplicateNames() {

        }

        public abstract Node<T> copy();

        public MutableComponent getPathName() {
            MutableComponent result = name.copy();
            Node<T> current = parent;
            while (current!= null) {
                result = current.name.copy().append(Text.literal("/")).append(result);
                current = current.parent;
            }
            return result;
        }

        public String getPathKey() {
            String result = key;
            Node<T> current = parent;
            while (current!= null) {
                result = current.key + "/" + result;
                current = current.parent;
            }
            return result;
        }

        public int getDepth() {
            int result = 0;
            Node<T> current = parent;
            while (current!= null) {
                result++;
                current = current.parent;
            }
            return result;
        }

        public String toString() {
            return getPathName().getString();
        }
    }

    public static class Branch<T> extends Node<T> {
        public Map<String, Branch<T>> branches = new HashMap<>();
        public Map<String, Data<T>> leaves = new HashMap<>();
        
        public Branch(String key, String name, Node<T> parent) {
            super(key, name, parent);
        }

        public Branch<T> addBranch(String key, String name) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(key);
            return branches.computeIfAbsent(key, k -> new Branch<>(k, name, this));
        }

        public Data<T> addLeaf(String key, String name, T data) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(key);
            return leaves.computeIfAbsent(key, k -> new Data<>(k, name, this, data));
        }

        public Branch<T> getBranch(String key) {
            return branches.get(key);
        }

        public Data<T> getLeaf(String key) {
            return leaves.get(key);
        }

        @Override
        protected void dealWithDuplicateNames() {
            List<String> usedNames = new ArrayList<>();
            for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                usedNames.add(entry.getValue().name.getString());
            }

            for (Map.Entry<String, Data<T>> entry : leaves.entrySet()) {
                usedNames.add(entry.getValue().name.getString());
            }
            for (Branch<T> branch : branches.values()) {
                if (Collections.frequency(usedNames, branch.name.getString()) > 1) {
                    branch.name.append(Text.literal("(" + branch.key + ")"));
                }
            }
            for (Data<T> leaf : leaves.values()) {
                if (Collections.frequency(usedNames, leaf.name.getString()) > 1) {
                    leaf.name.append(Text.literal("(" + leaf.key + ")"));
                }
            }
        }

        public Map<String, Node<T>> getNodes() {
            Map<String, Node<T>> result = new LinkedHashMap<>();
            result.putAll(branches);
            result.putAll(leaves);
            return result;
        }

        public boolean hasSubBranches() {
            return !branches.isEmpty();
        }

        public Map<String, Data<T>> mergeLevel() {
            Map<String, Data<T>> result = new HashMap<>();
            for (Map.Entry<String, Data<T>> entry : leaves.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                result.putAll(entry.getValue().mergeLevel());
            }
            
            List<String> usedNames = new ArrayList<>();

            for (Data<T> data : result.values()) {
                usedNames.add(data.name.getString());
            }

            for (Data<T> data : result.values()) {
                if (Collections.frequency(usedNames, data.name.getString()) > 1) {
                    data.name.append(Text.literal("(" + data.key + ")"));
                }
            }

            return result;
        }

        @Override
        public Branch<T> copy() {
            Branch<T> result = new Branch<>(key, name.getString(), parent);
            for (Map.Entry<String, Branch<T>> entry : branches.entrySet()) {
                result.branches.put(entry.getKey(), entry.getValue().copy());
            }

            for (Map.Entry<String, Data<T>> entry : leaves.entrySet()) {
                result.leaves.put(entry.getKey(), entry.getValue().copy());
            }
            return result;
        }
    }

    public static class Root<T> extends Branch<T> {
        public Root(String name) {
            super("root", name, null);
        }
    }

    public static class Data<T> extends Node<T> {
        public T data;
        public Data(String key, String name, Node<T> parent, T data) {
            super(key, name, parent);
            this.data = data;
        }

        @Override
        public Data<T> copy() {
            return new Data<>(key, name.getString(), parent, data);
        }
    }

    public static <T> Root<T> loadTree(String rootName, Map<String, T> map, Function<T, String> funGetName) {
        Root<T> root = new Root<>(rootName);
        for (Map.Entry<String, T> entry : map.entrySet()) {
            String[] path = entry.getKey().split("/", -1);
            if (path.length == 0) continue;
            
            Branch<T> branch = root;
            for (int i = 0; i < path.length - 1; i++) {
                branch = branch.addBranch(path[i], path[i]);
            }
            branch.addLeaf(path[path.length - 1], funGetName.apply(entry.getValue()), entry.getValue());
        }
        root.dealWithDuplicateNames();
        return root;
    }
}