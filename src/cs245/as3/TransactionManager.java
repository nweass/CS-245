package cs245.as3;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.prefs.PreferenceChangeEvent;


import cs245.as3.interfaces.LogManager;
import cs245.as3.interfaces.StorageManager;
import cs245.as3.interfaces.StorageManager.TaggedValue;




/**
 * You will implement this class.
 *
 * The implementation we have provided below performs atomic transactions but the changes are not durable.
 * Feel free to replace any of the data structures in your implementation, though the instructor solution includes
 * the same data structures (with additional fields) and uses the same strategy of buffering writes until commit.
 *
 * Your implementation need not be threadsafe, i.e. no methods of TransactionManager are ever called concurrently.
 *
 * You can assume that the constructor and initAndRecover() are both called before any of the other methods.
 */
public class TransactionManager {
	/**
	 * redo log format
	 */
	public static class Record {
		/**
		 * redo log type
		 * 0: tx start
		 * 1: tx a record
		 * 2: tx commit
		 * if type == 0：
		 * 		key = txID
		 * 		value = null
		 * 		length = len of record
		 * if type == 1
		 * 		key = key of data
		 * 		value = value of data
		 * 		length = len of record
		 *
		 * if type == 2
		 * 		key = txID
		 * 		value = null
		 * 		length = len of record
		 */
		public byte type;
		public long key;
		public byte[] value;
		/**
		 * 序列化后整条日志记录的长度
		 */
		public byte length;

		Record(byte type, long txID){
			this.type = type;
			this.key = txID;
			this.length = (byte)(Byte.BYTES + Byte.BYTES + Long.BYTES);
			this.value = new byte[0];
		}

		Record(long key, byte[] value) {
			this.type = 1;
			this.key = key;
			this.value = value;
			this.length = (byte)(Byte.BYTES + Byte.BYTES + Long.BYTES + value.length);
		}

		public byte[] serialize() {
			ByteBuffer ret = ByteBuffer.allocate(Byte.BYTES + Byte.BYTES + Long.BYTES + value.length);
			ret.put(type);
			ret.put(length);
			ret.putLong(key);
			ret.put(value);
			return ret.array();
		}

		public static Record deserialize(byte[] b) {

			ByteBuffer bb = ByteBuffer.wrap(b);
			byte type = bb.get();
			byte length = bb.get();
			long key = bb.getLong();
			byte[] value = new byte[b.length - Long.BYTES - Byte.BYTES - Byte.BYTES ];
			bb.get(value);

			return new Record(key, value);
		}
	}


	class WritesetEntry {
		public long key;
		public byte[] value;
		public WritesetEntry(long key, byte[] value) {
			this.key = key;
			this.value = value;
		}
	}
	/**
	 * Holds the latest value for each key.
	 */
	private HashMap<Long, TaggedValue> latestValues;
	/**
	 * Hold on to writesets until commit.
	 */
	private HashMap<Long, ArrayList<WritesetEntry>> writesets;

	private LogManager lm;

	private StorageManager storageManager;
	/**
	 * 用于寻找一次"持久化"中，最大的安全截断点
	 */
	HashSet<Long> set = new HashSet<>();

	long currentMaxTag = 0;

	int maxTagLen = 0;

	public TransactionManager() {
		writesets = new HashMap<>();
		//see initAndRecover
		latestValues = null;
	}

	/**
	 * Prepare the transaction manager to serve operations.
	 * At this time you should detect whether the StorageManager is inconsistent and recover it.
	 */
	public void initAndRecover(StorageManager sm, LogManager lm) {
		this.storageManager = sm;
		this.lm = lm;
		latestValues = sm.readStoredTable();
		//从安全截断点开始 进行恢复
		int startOffset = this.lm.getLogTruncationOffset();
		int endOffset = this.lm.getLogEndOffset();
		boolean isEnd = false;
		while(endOffset > startOffset){
			if(isEnd){
				break;
			}
			//获取一条日志的type和len
			byte[] typeAndLen = lm.readLogRecord(startOffset, 2);
			byte type = typeAndLen[0];
			byte len = typeAndLen[1];
			//如果是start record检查是否存在对于的commit record
			if (type == 0){
				startOffset += len;
				int testOffset = startOffset;
				byte aType = 0;
				byte aLen = 0;
				List<Record> dataList = new LinkedList<>();
				List<TaggedValue> taggedValueList = new LinkedList<>();
				//向前探测是否存在对应的commit record
				while (endOffset > testOffset){
					byte[] AtypeAndLen = lm.readLogRecord(testOffset, 2);
					aType = AtypeAndLen[0];
					aLen = AtypeAndLen[1];
//					将遇到的普通修改记录恢复成record缓存
					if (aType == 1){
						byte[] aRead_r = lm.readLogRecord(testOffset, aLen);
						Record aRecord = Record.deserialize(aRead_r);
						dataList.add(aRecord);
						taggedValueList.add(new TaggedValue(testOffset, aRecord.value));
					}
//					如果没有对于的commit record，说明不需要恢复，将缓存的record抛弃
					if (aType == 0){
						startOffset = testOffset;
						taggedValueList.clear();
						dataList.clear();
//					存在对应的commit record说明需要恢复，将缓存的record恢复到数据库
					}else if(aType == 2){
						int i = 0;
						for (Record record :dataList){
							TaggedValue taggedValue = latestValues.get(record.key);
							if(record.type == 1){
								if (taggedValue == null || latestValues.get(record.key).tag < taggedValueList.get(i).tag){
									storageManager.queueWrite(record.key, taggedValueList.get(i).tag, record.value);
									latestValues.put(record.key, taggedValueList.get(i));
								}
							}
							i++;
						}
//						startOffset跳转到下一条start record，减少IO
						startOffset = testOffset + aLen;
					}
					testOffset += aLen;
					if (testOffset >= endOffset){
						isEnd = true;
					}
				}
			} else{
				startOffset += len;
			}

		}
	}

	/**
	 * Indicates the start of a new transaction. We will guarantee that txID always increases (even across crashes)
	 */
	public void start(long txID) {
		// TODO: Not implemented for non-durable transactions, you should implement this
		//递增性校验和正负校验
//		if(txID <= this.maxTransactionId){
//			throw new IllegalArgumentException("txID " + txID +
//					" smaller than current maximum txID " + maxTransactionId + "or is negative number");
//		}
		writesets.put(txID, new ArrayList<>());
		return;
	}

	/**
	 * Returns the latest committed value for a key by any transaction.
	 */
	public byte[] read(long txID, long key) {
		TaggedValue taggedValue = latestValues.get(key);
		return taggedValue == null ? null : taggedValue.value;
	}

	/**
	 * Indicates a write to the database. Note that such writes should not be visible to read()
	 * calls until the transaction making the write commits. For simplicity, we will not make reads
	 * to this same key from txID itself after we make a write to the key.
	 */
	public void write(long txID, long key, byte[] value) {
		ArrayList<WritesetEntry> writeset = writesets.get(txID);
		if (writeset == null) {
			writeset = new ArrayList<>();
			writesets.put(txID, writeset);
		}
		writeset.add(new WritesetEntry(key, value));
	}
	/**
	 * Commits a transaction, and makes its writes visible to subsequent read operations.\
	 */
	public void commit(long txID) {
		ArrayList<WritesetEntry> writeset = writesets.get(txID);
		if (writeset != null) {
			lm.appendLogRecord(new Record((byte)0, txID).serialize());
			//使用ByteBuffer将小于128的record缓存，当buffer.length > 128,再批量写入日志
			ByteBuffer aCommit = ByteBuffer.allocate(128);
			int tag = lm.getLogEndOffset();
			int i = 0;
			List<TaggedValue> dataList = new LinkedList<>();
			for(WritesetEntry x : writeset) {
				Record currentRecord = new Record(x.key, x.value);
				if (aCommit.position() +currentRecord.length < aCommit.capacity() && i != writeset.size() - 1){
					aCommit.put(currentRecord.serialize());
				}else{
					byte[] temp = new byte[aCommit.position()];
					System.arraycopy(aCommit.array(), 0, temp, 0, temp.length);
					lm.appendLogRecord(temp);
					lm.appendLogRecord(currentRecord.serialize());
					aCommit = ByteBuffer.allocate(128);
				}
				dataList.add(new TaggedValue(tag, x.value));
				tag += currentRecord.serialize().length;
				i++;
			}
			lm.appendLogRecord(new Record((byte)2, txID).serialize());
			int j = 0;
			for (WritesetEntry x : writeset){
				latestValues.put(x.key, dataList.get(j));
				storageManager.queueWrite(x.key, dataList.get(j).tag, x.value);
				j++;
			}
			writesets.remove(txID);
		}
	}
	/**
	 * Aborts a transaction.
	 */
	public void abort(long txID) {
		writesets.remove(txID);
	}

	/**
	 * The storage manager will call back into this procedure every time a queued write becomes persistent.
	 * These calls are in order of writes to a key and will occur once for every such queued write, unless a crash occurs.
	 */
	public void writePersisted(long key, long persisted_tag, byte[] persisted_value) {
		//将一次持久化的中最大的persisted_tag
		if(persisted_tag > currentMaxTag){
			currentMaxTag = persisted_tag;
			maxTagLen = (int)persisted_tag + persisted_value.length + 10;
		}
		set.add(key);
		//持久化结束，写入最大persisted_tag 的下一条日志的偏移量（必定是一条start commit）
		if(set.size() == latestValues.size()){
			this.lm.setLogTruncationOffset(maxTagLen);
//			set.clear();
		}
	}
}
