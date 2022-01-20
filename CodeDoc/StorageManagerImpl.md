# StorageManager解析

## 结构图

概述：每个数据由一个entry构成，entry是TaggedValue的双端队列，这个集合保存了Key相同的数据的不同版本，对一个key对应的value的每次修改都会产生新的版本，即实现了undo log的版本链机制

## 接口

使用哈希表为内存结构，用于拷贝已持久化的数据，只在恢复期使用，queueWrite提供持久化写入

```java
public interface StorageManager {
	public static class TaggedValue {
		public final long tag;
		public final byte[] value;
		
		public TaggedValue(long tag, byte[] value) {
			this.tag = tag;
			this.value = value;
		}
	}

	public HashMap<Long, TaggedValue> readStoredTable();


	public void queueWrite(long key, long tag, byte[] value);
}
```

## 实现

### field

```java

	private final ConcurrentHashMap<Long, StorageManagerEntry> entries;
	//标示不需要进行持久化的键，用于test
	private long[] dont_persist_keys;
	//标识是否处于恢复状态
	protected boolean in_recovery;
	//
	private TransactionManager persistence_listener;
```

#### 内部类StorageManagerEntry 

由TaggedValue队列和两个指针组成

```java
private class StorageManagerEntry {
		//latest_version always points to the end of versions
		//persisted_version always points to the start of versions
		volatile TaggedValue latest_version;
		volatile TaggedValue persisted_version;
		ArrayDeque<TaggedValue> versions;
		
		public StorageManagerEntry() {
			versions = new ArrayDeque<>();
			latest_version = null;
			persisted_version = null;
		}
	}
```

#### 内部类TaggedValue

单个数据的结构，KV类型

```java
class TaggedValue{
    public final long tag;
    public final byte[] value;
     
    public TaggedValue(long tag, byte[] value){
        this.tag = tag;
        this.value = value;
    }
}

```

### method

#### queueWrite

新建一个TaggedValue，放入entries中

```java
	public void queueWrite(long key, long tag, byte[] value) {
		StorageManagerEntry entry = entries.get(key);
		if (entry == null) {
			entry = new StorageManagerEntry();
			StorageManagerEntry prior_entry = entries.putIfAbsent(key, entry);
			if (prior_entry != null) {
				entry = prior_entry;
			}
		}
		TaggedValue tv = new TaggedValue(tag, value);
		synchronized(entry) {
			entry.latest_version = tv;
			entry.versions.add(tv);
		}
	}
```

* 使用同步锁更新entry，为什么在此处使用同步，考虑锁的粒度

#### readStoredTable

在恢复时调用，获取每个KV数据的持久化版本

```java
public HashMap<Long, TaggedValue> readStoredTable() {
		if (!in_recovery) {
			throw new RuntimeException("Call to readStoredTable outside of recovery.");
		}
		HashMap<Long, TaggedValue> toRet = new HashMap<>();
		for(Entry<Long, StorageManagerEntry> entry : entries.entrySet()) {
			StorageManagerEntry sme = entry.getValue();
			if (sme.persisted_version != null) {
				toRet.put(entry.getKey(), sme.persisted_version);
			}
		}
		return toRet;
	}
```

#### shouldPersist

判断是否可以持久化，即不在不可持久化数组中

####  crash

删除所有未初始化版本，用于模拟崩溃

#### persist

标识是否存在未持久化版本，通过比较版本指针实现

循环删除旧版本，并只剩下一个版本

#### do_persistence_work

遍历调用persist