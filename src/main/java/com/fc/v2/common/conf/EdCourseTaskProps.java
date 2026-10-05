package com.fc.v2.common.conf;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 选课催办派单班次配置（ed.course-task）。
 * <p>派单分时段：白天一档（day-start~day-end），夜间与法定节假日整段不派；
 * 选课高峰那三天（peak-dates，教务处划定）单开高峰档，不走日常档。
 * 窗口外到期的提醒放着不动，等下一轮。</p>
 *
 * @author fuce
 * @date 2026-10-04
 */
@Component
@ConfigurationProperties(prefix = "ed.course-task")
public class EdCourseTaskProps {

    /** 日常档 */
    public static final String SLOT_DAY = "DAY";
    /** 高峰档（选课高峰三天单开） */
    public static final String SLOT_PEAK = "PEAK";
    /** 窗口外（夜间/法定节假日），本轮不派 */
    public static final String SLOT_NONE = "NONE";

    /** 日常档开窗时刻，默认 08:00 */
    private String dayStart = "08:00";

    /** 日常档关窗时刻（不含），默认 20:00 */
    private String dayEnd = "20:00";

    /** 高峰档开窗时刻，默认 08:00 */
    private String peakStart = "08:00";

    /** 高峰档关窗时刻（不含），默认 22:00 */
    private String peakEnd = "22:00";

    /** 选课高峰日期（yyyy-MM-dd），教务处划定的那三天 */
    private List<String> peakDates = new ArrayList<String>();

    /** 法定节假日（yyyy-MM-dd），整段不派 */
    private List<String> holidays = new ArrayList<String>();

    /** 在册辅导员代号（派单候选，按手头在催人数派） */
    private List<String> counselors = new ArrayList<String>();

    /** 浮出超过几天没催出去顶到最前头，默认 3 */
    private int agedDays = 3;

    /**
     * 这一刻落在哪一档：法定节假日整段不派；高峰三天走高峰档；
     * 其余日子走日常档；窗口外一律 NONE。
     */
    public String slotOf(Date at) {
        String day = LocalDate.from(at.toInstant().atZone(ZoneId.systemDefault())).toString();
        if (this.holidays.contains(day)) {
            return SLOT_NONE;
        }
        LocalTime time = at.toInstant().atZone(ZoneId.systemDefault()).toLocalTime();
        if (this.peakDates.contains(day)) {
            return inWindow(time, this.peakStart, this.peakEnd) ? SLOT_PEAK : SLOT_NONE;
        }
        return inWindow(time, this.dayStart, this.dayEnd) ? SLOT_DAY : SLOT_NONE;
    }

    private static boolean inWindow(LocalTime time, String start, String end) {
        LocalTime s = LocalTime.parse(start);
        LocalTime e = LocalTime.parse(end);
        return !time.isBefore(s) && time.isBefore(e);
    }

    public String getDayStart() {
        return dayStart;
    }

    public void setDayStart(String dayStart) {
        this.dayStart = dayStart;
    }

    public String getDayEnd() {
        return dayEnd;
    }

    public void setDayEnd(String dayEnd) {
        this.dayEnd = dayEnd;
    }

    public String getPeakStart() {
        return peakStart;
    }

    public void setPeakStart(String peakStart) {
        this.peakStart = peakStart;
    }

    public String getPeakEnd() {
        return peakEnd;
    }

    public void setPeakEnd(String peakEnd) {
        this.peakEnd = peakEnd;
    }

    public List<String> getPeakDates() {
        return peakDates;
    }

    public void setPeakDates(List<String> peakDates) {
        this.peakDates = peakDates;
    }

    public List<String> getHolidays() {
        return holidays;
    }

    public void setHolidays(List<String> holidays) {
        this.holidays = holidays;
    }

    public List<String> getCounselors() {
        return counselors;
    }

    public void setCounselors(List<String> counselors) {
        this.counselors = counselors;
    }

    public int getAgedDays() {
        return agedDays;
    }

    public void setAgedDays(int agedDays) {
        this.agedDays = agedDays;
    }
}
