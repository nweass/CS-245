# LogManagerImpl解析

## 接口

实现LogManager接口

```java
public interface LogManager {
	

	public int getLogEndOffset();


	public byte[] readLogRecord(int offset, int size);


	public int appendLogRecord(byte[] record);

	public int getLogTruncationOffset();

	public void setLogTruncationOffset(int offset);
}
```

## Feild

```java
//日志本体数据结构，借助java nio bytebuffer实现
private ByteBuffer log；
//日志大小
private int logSize;
//有效日志位移起始点，即从该点之后的日志代表的数据改动还未落盘或还不能废弃的日志
private int logTruncationOffset;
//读操作计数
private int Riop_counter;
//写操作计数
private int Wiop_counter;

private int nIOBeforeCrash;

private boolean serveRequesets;

// 1 G totalLogSize
private static final int BUFSIZE_START = 1000000000;

// 128 byte max record size
private static final int RECORD_SIZE = 128;
```

betybuffer：[图解java中的bytebuffer - 张伯雨 - 博客园 (cnblogs.com)](https://www.cnblogs.com/zhangboyu/p/7452587.html)

## Method

### getLogEngOffset

很明显，获得最后一条日志的位移，通过维护logSize实现

```java
@Override
public int getLogEndOffset() {
    return logSize;
}
```

### readLogRecord

获取从指定position开始的日志内容

```java
public byte[] readLogRecord(int position, int size) throws ArrayIndexOutOfBoundsException {
		checkServeRequest();
    	//校验position的有效性，必须大于有效日志起始点，position + size小于日志终点
		if ( position < logTruncationOffset || position+size > getLogEndOffset() ) {
			throw new ArrayIndexOutOfBoundsException("Offset " + (position+size) + "invalid: log start offset is " + logTruncationOffset +
					", log end offset is " + getLogEndOffset());
		}
		//size有效性校验，大于零，小于单条日志格式大小
		if ( size > RECORD_SIZE ) {
			throw new IllegalArgumentException("Record length " + size +
					" greater than maximum allowed length " + RECORD_SIZE);
		}
		//实际从log中获取byte[]
		byte[] ret = new byte[size];
		log.position(position);
		log.get(ret);
		Riop_counter++;
		return ret;
	}
```

* 为什么size不是必须等于RecordSize，日志大小是否不等

### appendLogRecord

原子性追加写入log，并持久化

```java
public int appendLogRecord(byte[] record) {
		checkServeRequest();
    	//记录长度合法性校验
		if ( record.length > RECORD_SIZE ) {
			throw new IllegalArgumentException("Record length " + record.length +
					" greater than maximum allowed length " + RECORD_SIZE);
		}
		//原子性-》同步锁...
		synchronized(this) {
			Wiop_counter++;
			
			log.position(logSize);

			for ( int i = 0; i < record.length; i++ ) {
				log.put(record[i]);
			}

			int priorLogSize = logSize;

			logSize += record.length;
			return priorLogSize;
		}
	}
```

* 此处原子性指不会被切换线程

### get/setLogTruncationOffset

获得当前有效日志起始点，实现与logSize类似

设置有效日志起始点

```java
	@Override
	public int getLogTruncationOffset() {
		return logTruncationOffset;
	}

	@Override
	public void setLogTruncationOffset(int logTruncationOffset) {
		//递增
        if (logTruncationOffset > logSize || logTruncationOffset < this.logTruncationOffset) {
			throw new IllegalArgumentException();
		}

		this.logTruncationOffset = logTruncationOffset;
	}
```

### checkServeRequset

用于模拟崩溃，每次写入日志都会调用一次，当nIOSBeforeCrash减为零后，人为抛出异常模拟崩溃

```java

private void checkServeRequest() {
		if (nIOSBeforeCrash > 0) {
			nIOSBeforeCrash--;
			if (nIOSBeforeCrash == 0) {
				serveRequests = false;
			}
		}
		if (!serveRequests) {
			//Crash!
			throw new CrashException();
		}
   		assert(!Thread.interrupted()); //Cooperate with timeout:
	}
```

