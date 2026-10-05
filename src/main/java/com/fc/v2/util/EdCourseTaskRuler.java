package com.fc.v2.util;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * 选课催办截止口径（全校一把尺子）。
 * <p>算"到没到期该浮出"和算"还剩几天"只许走这一个类：浮出查询（listDue）与
 * 派单回写（runOnce）用同一个方法，不许 SQL 算一遍、Java 又算一遍。</p>
 * <p>口径约定：</p>
 * <ul>
 *   <li>还剩几天＝两个自然日之差（截止日 − 当日），与时刻无关；</li>
 *   <li>到期该浮出 ⇔ 还剩天数 &le; 单条提前天数（amount 按整自然日取，小数不计）；</li>
 *   <li>截止已过 ⇔ 当前时刻严格晚于 due_at（截止那一天最后一刻）。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-10-04
 */
public final class EdCourseTaskRuler {

    private EdCourseTaskRuler() {
    }

    /** 还剩几个自然日（同日为 0，过了截止为负）。 */
    public static long remainingDays(Date at, Date dueAt) {
        LocalDate day = toDay(at);
        LocalDate dueDay = toDay(dueAt);
        return ChronoUnit.DAYS.between(day, dueDay);
    }

    /** 到没到期该浮出：还剩天数 &le; 单条提前天数。 */
    public static boolean isDue(Date at, Date dueAt, BigDecimal amount) {
        if (at == null || dueAt == null || amount == null) {
            return false;
        }
        return remainingDays(at, dueAt) <= amount.longValue();
    }

    /** 截止那一刻过没过（撤单/改用途的前置条件）。 */
    public static boolean isPastDue(Date at, Date dueAt) {
        if (at == null || dueAt == null) {
            return false;
        }
        return at.getTime() > dueAt.getTime();
    }

    /** 已经浮出来几天（&gt;3 天的顶到派发最前头）。 */
    public static long surfacedDays(Date at, Date dueAt, BigDecimal amount) {
        return amount.longValue() - remainingDays(at, dueAt);
    }

    /** 该浮出的那一刻（due_at 往前推提前天数），仅用于派单排序。 */
    public static Date surfaceAt(Date dueAt, BigDecimal amount) {
        return new Date(dueAt.getTime() - amount.longValue() * 24L * 3600L * 1000L);
    }

    private static LocalDate toDay(Date d) {
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
