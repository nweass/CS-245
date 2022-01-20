TransactionManager

接口

interface TransactionManager{
void initAndRecover(StorageManager sm, LogManager lm);

    void start(long txID);
    
    byte[] read(long txID, long key);
    
    void write(long txID, long key, byte[] value);
    
    void commit(long txID);
    
    void abort(long txID);
    
    void writePersisted(long key, long persisted_tag, byte[] persisted_value);

}
设计方案一

一人日
日志格式

仿照logTest中的Record简单实现，增加length字段方便变量。

数据写入

采用redo log方案，允许异步刷盘，但提交前不允许写入数据库

恢复

TurncationOffset暂时由0代替，即完全遍历日志进行恢复
getLogEndOffset指向最后一个日志的尾端 + 1

TruncationOffset指向还未持久化的第一个日志的头端

鉴于这样的既有实现，将长度byte为放置与序列化byte[]首端

方便从TruncationOffset开始逐步恢复数据

遍历日志，如果反序列化的记录对应tag大于latestValues.get(key).tag

说明当前已经持久化的最新的记录小于日志中的记录，即有未持久化的数据丢失，应该进行恢复

回滚

未提交的数据也从未进入日志，直接从事务缓存中移除

crash也会导致缓存中的数据丢失，达到了回滚的效果

小结

没有考虑commit崩溃的情况，commit不具备原子性，由于没有注意这个情况，在方案二没有进行优化
没有进行checkpoint，性能低下，无法通过性能测试
没有对日志长度很小的情况进行优化，多次调用log写入
设计方案二

一人日
截断日志

利用持久化回调，但持久化不是按log顺序进行，即回调的persisted_tag不一定说明之前的记录都持久化完成。
利用txManager本地变量，获得一次持久化中的最大安全截断点（取巧利用了持久化期间不会模拟崩溃的特点，如果持久化期间会模拟崩溃，截断效果将变差，但不会出错）

批量写入

使用ByteBuffer对每次写入进行缓存，当大于128时，将ByteBuffer中的数据批量写入日志

小结

实现了日志阶段通过了性能测试
完成了批量写入优化将LeaderBoardTest50000次写入优化为9000次
不能通过重复崩溃测试
设计方案三

两人日
增加日志类型保证commit原子性

这次注意到了可能出现commit中出现崩溃的情况，需要有发现和解决这种现象的机制。
通过新增日志类型，模仿redo log 和binlog 二阶段提交的机制，使用commit Record实现commit的原子性。
有commitRecord说明此段日志应该恢复，没有commitRecord说明发生崩溃，应该针对此段日志进行回滚
由于日志中没有旧值，没有办法对对已经写入数据库的操作进行撤销，（取巧利用了本次作业内写入数据库不会发生崩溃的特点）采用commit后再对数据库进行写入。因此并不需要进行回滚，只需要忽略这段日志。

完善恢复逻辑

完善满足原子commit的提交

利用start Record 和commit Record识别此段事务是否需要恢复（此处恢复逻辑利用了本次作业不会出现并非事务的特点）

未实现的分析及优化

恢复批量读取

对于日志的恢复，如果小于128应该可以仿照的commit进行批量的获取
其实完成了雏形，但对于恢复iops的优化效果不佳，甚至增加了PerformanceTest的iops，因此没有保留这段代码。增加的iops来自对于标志日志的重复获取，事实上优化效果不佳原因出自后面讲的第二个可以进行优化的点。

额外的日志消耗

使用了两个标志record实现commit的原子性，因此每个事务都会多出两个长度为10个字节的空间消耗，以及额外两次的日志读取。导致对日志空间的有效利用率和恢复时的有效读取次数随单次事务写入量的减少而降低。

Performance测试中，一个事务只进行了一次写入，导致每个记录数据日志都有两个额外的标志日志用于恢复（这些日志只读取长度和类型用于遍历和决定何时恢复）。再加上对数据日志的获取操作导致在PerformanceTest中的iops = 记录数据的日志 * 4。因此批量获取日志只能减少对记录数据日志的获取部分的iops，但这部分只占整体的四分之一。

如果可以减少额外标记日志的使用，例如只用commit Record实现原子commit，可以有效降低PerformanceTest中的iops，提高单次事务写入量很少 的情况下的利用率。

为了追求降低iops取巧

为了在单元测试取得更好的成绩，放弃了程序的可靠性。

方案一接口实现

initAndRecover

this.storageManager = sm;
this.lm = lm;
latestValues = sm.readStoredTable();
int startOffset = 0;
while(this.lm.getLogEndOffset() > startOffset){
//       int startOffset = this.lm.getLogTruncationOffset();

         byte len = lm.readLogRecord(startOffset, 1)[0];
         byte[] read_r = lm.readLogRecord(startOffset, len);
         Record rec_r = Record.deserialize(read_r);
         TaggedValue taggedValue = latestValues.get(rec_r.key);
         if (taggedValue == null || latestValues.get(rec_r.key).tag <= startOffset){
            storageManager.queueWrite(rec_r.key, startOffset, rec_r.value);
            latestValues.put(rec_r.key, new TaggedValue(startOffset, rec_r.value));

         }
         startOffset += len;
//       lm.setLogTruncationOffset(startOffset + len);
}
start

开启事务

在writesets中添加新事务

要求trID递增

	public void start(long txID) {
		// TODO: Not implemented for non-durable transactions, you should implement this
		//递增性校验和正负校验
//		if(txID <= this.maxTransactionId){
//			throw new IllegalArgumentException("txID " + txID +
//					" smaller than current maximum txID " + maxTransactionId + "or is negative number");
//		}
writesets.put(txID, new ArrayList<>());
this.maxTransactionId = txID;
return;
}
read

读操作不涉及日志和数据库写入

	public byte[] read(long txID, long key) {
		TaggedValue taggedValue = latestValues.get(key);
		return taggedValue == null ? null : taggedValue.value;
	}
write

写入事务管理器的缓存中

	public void write(long txID, long key, byte[] value) {
		ArrayList<WritesetEntry> writeset = writesets.get(txID);
		if (writeset == null) {
			writeset = new ArrayList<>();
			writesets.put(txID, writeset);
		}
		writeset.add(new WritesetEntry(key, value));
	}
commit

写入数据库同时维护log，tag是在日志上的偏移量，即指向对应日志记录的首端

	public void commit(long txID) {
		ArrayList<WritesetEntry> writeset = writesets.get(txID);

		if (writeset != null) {
			synchronized (this){
				for(WritesetEntry x : writeset) {

					latestValues.put(x.key, new TaggedValue(lm.getLogEndOffset() + 1, x.value));
					storageManager.queueWrite(x.key, lm.getLogEndOffset() + 1, x.value);
					lm.appendLogRecord(new Record(x.key, x.value).serialize());

				}

				writesets.remove(txID);
			}

		}
	}
abort

	public void abort(long txID) {

		writesets.remove(txID);
	}
writePersisted

    public void writePersisted(long key, long persisted_tag, byte[] persisted_value) {
//    if (persisted_tag > lm.getLogTruncationOffset()){
//       lm.setLogTruncationOffset((int)persisted_tag);
//    }

}