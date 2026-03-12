package cn.wildfirechat.archive.service;

import org.springframework.stereotype.Component;

import java.util.Calendar;
import java.util.Date;

/**
 * 消息ID工具类
 * 参考 MessageShardingUtil.java
 */
@Component
public class MessageIdUtil {
    
    private static final int NODE_ID_WIDTH = 6;
    private static final int ROTATE_ID_WIDTH = 15;
    private static final long T201801010000 = 1514736000000L;
    
    /**
     * 从消息ID获取时间戳
     * @param mid 消息ID
     * @return 时间戳（毫秒）
     */
    public long getTimestampFromMid(long mid) {
        long timestamp = (mid >> (NODE_ID_WIDTH + ROTATE_ID_WIDTH)) + T201801010000;
        return timestamp;
    }
    
    /**
     * 从消息ID获取日期
     * @param mid 消息ID
     * @return Date对象
     */
    public Date getDateFromMid(long mid) {
        return new Date(getTimestampFromMid(mid));
    }
    
    /**
     * 从消息ID获取Calendar
     * @param mid 消息ID
     * @return Calendar对象
     */
    public Calendar getCalendarFromMid(long mid) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(getDateFromMid(mid));
        return calendar;
    }
    
    /**
     * 从时间戳生成消息ID（最小可能值）
     * @param timestamp 时间戳（毫秒）
     * @return 消息ID
     */
    public long getMidFromTimestamp(long timestamp) {
        long id = timestamp - T201801010000;
        id <<= ROTATE_ID_WIDTH;
        id <<= NODE_ID_WIDTH;
        return id;
    }
    
    /**
     * 获取消息所属表索引
     * @param mid 消息ID
     * @return 表索引 (0-35)
     */
    public int getTableIndex(long mid) {
        Calendar calendar = getCalendarFromMid(mid);
        int month = calendar.get(Calendar.MONTH);
        int year = calendar.get(Calendar.YEAR);
        year %= 3;
        return year * 12 + month;
    }
    
    /**
     * 获取消息表名
     * @param mid 消息ID
     * @return 表名
     */
    public String getMessageTableName(long mid) {
        int index = getTableIndex(mid);
        return String.format("t_messages_%d", index);
    }
    
    /**
     * 获取月份值 (用于分区)
     * @param mid 消息ID
     * @return 月份值 (year * 100 + month)
     */
    public int getMsgMonth(long mid) {
        Calendar calendar = getCalendarFromMid(mid);
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH) + 1; // 1-12
        return year * 100 + month;
    }
}
