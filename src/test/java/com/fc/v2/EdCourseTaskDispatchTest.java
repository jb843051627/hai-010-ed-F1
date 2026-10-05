package com.fc.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.conf.EdCourseTaskProps;
import com.fc.v2.mapper.auto.TEdCourseTaskLogMapper;
import com.fc.v2.mapper.auto.TEdCourseTaskMapper;
import com.fc.v2.model.auto.TEdCourseTask;
import com.fc.v2.model.auto.TEdCourseTaskLog;
import com.fc.v2.model.custom.EdCourseTaskRound;
import com.fc.v2.service.impl.EdCourseTaskWriter;
import com.fc.v2.service.impl.TEdCourseTaskServiceImpl;
import com.fc.v2.util.EdCourseTaskRuler;

/**
 * 选课催办派单一轮的规矩，逐条对着叙事验。H2（MODE=MySQL）跑真实 SQL：
 * 条件更新、唯一键、汇总计数都过真库，不 mock。
 *
 * @author fuce
 * @date 2026-10-04
 */
public class EdCourseTaskDispatchTest {

    private static SqlSession session;
    private static TEdCourseTaskMapper taskMapper;
    private static TEdCourseTaskLogMapper logMapper;

    private EdCourseTaskProps props;
    private EdCourseTaskWriter writer;
    private TEdCourseTaskServiceImpl service;

    @BeforeAll
    static void boot() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:ed_course_task;MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        try (Connection conn = ds.getConnection()) {
            ScriptRunner runner = new ScriptRunner(conn);
            runner.setLogWriter(null);
            runner.setErrorLogWriter(null);
            runner.setStopOnError(true);
            runner.runScript(new InputStreamReader(
                    EdCourseTaskDispatchTest.class.getResourceAsStream("/ed-course-task-h2.sql"), "UTF-8"));
        }
        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(TEdCourseTaskMapper.class);
        cfg.addMapper(TEdCourseTaskLogMapper.class);
        cfg.setEnvironment(new Environment("test", new JdbcTransactionFactory(), ds));
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        session = factory.openSession(true);
        taskMapper = session.getMapper(TEdCourseTaskMapper.class);
        logMapper = session.getMapper(TEdCourseTaskLogMapper.class);
    }

    @BeforeEach
    void reset() {
        taskMapper.delete(null);
        logMapper.delete(null);
        props = new EdCourseTaskProps();
        props.setCounselors(Arrays.asList("辅导员A", "辅导员B"));
        writer = new EdCourseTaskWriter();
        writer.setEdCourseTaskMapper(taskMapper);
        writer.setEdCourseTaskLogMapper(logMapper);
        service = new TEdCourseTaskServiceImpl();
        service.setEdCourseTaskMapper(taskMapper);
        service.setEdCourseTaskProps(props);
        service.setEdCourseTaskWriter(writer);
        service.setHoldDecider(null);
    }

    // ================= 口径：同一把尺子 =================

    @Test
    void dueBoundaryUsesSingleRuler() {
        Date due = d("2026-10-10 23:59:59");
        BigDecimal amount = new BigDecimal("3");
        // 还剩正好 3 天：到期该浮出
        assertTrue(EdCourseTaskRuler.isDue(d("2026-10-07 00:00:00"), due, amount));
        // 还剩 4 天：不到
        assertFalse(EdCourseTaskRuler.isDue(d("2026-10-06 23:59:59"), due, amount));
        // 截止过了还没派出去：仍算到期（等撤单或改用途）
        assertTrue(EdCourseTaskRuler.isDue(d("2026-10-11 00:00:00"), due, amount));
        assertEquals(3L, EdCourseTaskRuler.remainingDays(d("2026-10-07 08:00:00"), due));
        assertEquals(-1L, EdCourseTaskRuler.remainingDays(d("2026-10-11 08:00:00"), due));

        // 浮出清单只捞待派：已派/挂起/已撤/已删的一律不进
        newTask("R-待派", "2026-10-10 23:59:59", 3);
        newTaskWithStatus("R-已派", 1);
        newTaskWithStatus("R-挂起", 2);
        newTaskWithStatus("R-已撤", 3);
        TEdCourseTask deleted = newTask("R-已删", "2026-10-10 23:59:59", 3);
        deleted.setDelFlag(1);
        taskMapper.updateById(deleted);
        newTask("R-未到", "2026-10-20 23:59:59", 3);

        List<TEdCourseTask> due1 = service.listDue(d("2026-10-07 08:00:00"));
        assertEquals(1, due1.size());
        assertEquals("R-待派", due1.get(0).getItemNo());
    }

    // ================= 时段：夜间/节假日/高峰档 =================

    @Test
    void nightHolidayAndWindowEdgesBlockTheRound() {
        TEdCourseTask t1 = newTask("W-1", "2026-10-08 23:59:59", 3);
        // 日常档开窗边界：08:00 开（含）
        EdCourseTaskRound open = service.runOnce(d("2026-10-07 08:00:00"));
        assertTrue(open.isWindowOpen());
        assertEquals(1, open.getDispatchedCount());

        // 关窗边界：20:00 起（不含）算夜间，整段不派
        TEdCourseTask t2 = newTask("W-2", "2026-10-08 23:59:59", 3);
        EdCourseTaskRound night = service.runOnce(d("2026-10-07 20:00:00"));
        assertFalse(night.isWindowOpen());
        assertEquals(0, night.getDispatchedCount());
        assertEquals(0, statusOf(t2.getId()));

        // 法定节假日白天也不派
        props.setHolidays(Collections.singletonList("2026-10-08"));
        EdCourseTaskRound holiday = service.runOnce(d("2026-10-08 10:00:00"));
        assertFalse(holiday.isWindowOpen());
        assertEquals(0, statusOf(t2.getId()));
        assertEquals(0, logCount(t2.getId(), "DISPATCH"));
        // 窗口外放着不动：没派时刻、没受派人
        TEdCourseTask t2After = taskMapper.selectById(t2.getId());
        assertNull(t2After.getDispatchedAt());
        assertNull(t2After.getCounselor());
    }

    @Test
    void peakDaysUsePeakSlotNotDailySlot() {
        props.setPeakDates(Collections.singletonList("2026-10-09"));
        TEdCourseTask t = newTask("P-1", "2026-10-10 23:59:59", 3);
        // 高峰日 21:00：日常档已关，高峰档（08:00-22:00）还开着
        EdCourseTaskRound peak = service.runOnce(d("2026-10-09 21:00:00"));
        assertTrue(peak.isWindowOpen());
        assertEquals("PEAK", peak.getSlot());
        assertEquals(1, peak.getDispatchedCount());
        assertEquals("PEAK", logOf(t.getId(), "DISPATCH").getSlot());

        // 非高峰日同一时刻：窗口外，不动
        TEdCourseTask t2 = newTask("P-2", "2026-10-11 23:59:59", 3);
        EdCourseTaskRound plain = service.runOnce(d("2026-10-10 21:00:00"));
        assertFalse(plain.isWindowOpen());
        assertEquals(0, statusOf(t2.getId()));
    }

    // ================= 派单：落笔、幂等 =================

    @Test
    void dispatchWritesTimeAndFlipsStatusAtomicallyAndIdempotently() {
        Date at = d("2026-10-07 09:00:00");
        TEdCourseTask t = newTask("D-1", "2026-10-09 23:59:59", 3);

        EdCourseTaskRound round = service.runOnce(at);
        assertEquals(1, round.getDispatchedCount());
        TEdCourseTask after = taskMapper.selectById(t.getId());
        // 派发时刻写上＋情形待派翻已派，两样齐全才算成
        assertEquals(1, after.getStatus().intValue());
        assertEquals("辅导员A", after.getCounselor());
        assertNotNull(after.getDispatchedAt());
        assertEquals(at.getTime() / 1000, after.getDispatchedAt().getTime() / 1000);
        assertEquals(1, logCount(t.getId(), "DISPATCH"));

        // 这一轮派出去的，下一轮不再当待派捞出来；触发点再撞同一刻也只留一条
        EdCourseTaskRound again = service.runOnce(at);
        assertTrue(again.isEmpty());
        assertEquals(0, again.getDispatchedCount());
        assertFalse(writer.dispatch(after, "辅导员B", at, "DAY"));
        assertEquals(1, logCount(t.getId(), "DISPATCH"));
        assertEquals("辅导员A", taskMapper.selectById(t.getId()).getCounselor());
    }

    @Test
    void agedItemsJumpToFrontAndNormalOnesKeepFifo() {
        Date at = d("2026-10-07 09:00:00");
        // X 已浮出 6 天没催出去（>3 天），顶到最前头
        TEdCourseTask x = newTask("O-超期", "2026-10-06 23:59:59", 5);
        // Y、Z 普通：按浮出先后排队，不许插队（Y 10-05 浮出，Z 10-06 浮出）
        TEdCourseTask z = newTask("O-普通后", "2026-10-08 23:59:59", 2);
        TEdCourseTask y = newTask("O-普通先", "2026-10-09 23:59:59", 4);

        EdCourseTaskRound round = service.runOnce(at);
        assertEquals(3, round.getDispatchedCount());
        // 派发顺序 = 名单顺序：X 先派（给A），Y 次之（给B），Z 最后（又回到A）
        assertEquals("辅导员A", taskMapper.selectById(x.getId()).getCounselor());
        assertEquals("辅导员B", taskMapper.selectById(y.getId()).getCounselor());
        assertEquals("辅导员A", taskMapper.selectById(z.getId()).getCounselor());
    }

    @Test
    void leastLoadedCounselorTakesNextIdleFirst() {
        // 辅导员A 手头已有 1 条在催
        TEdCourseTask busy = newTask("L-在催", "2026-10-09 23:59:59", 3);
        busy.setStatus(1);
        busy.setCounselor("辅导员A");
        busy.setDispatchedAt(d("2026-10-06 09:00:00"));
        taskMapper.updateById(busy);

        TEdCourseTask t1 = newTask("L-新1", "2026-10-09 23:59:59", 3);
        TEdCourseTask t2 = newTask("L-新2", "2026-10-09 23:59:59", 3);
        EdCourseTaskRound round = service.runOnce(d("2026-10-07 09:00:00"));
        assertEquals(2, round.getDispatchedCount());
        // 手头空的先接：第一条给 B；第二条 A、B 各 1 条，按名单先后给 A
        assertEquals("辅导员B", taskMapper.selectById(t1.getId()).getCounselor());
        assertEquals("辅导员A", taskMapper.selectById(t2.getId()).getCounselor());
    }

    // ================= 办不成的：挂起与撤单 =================

    @Test
    void suspendWritesReasonAndDoesNotDragTheRound() {
        TEdCourseTask x = newTask("S-退学", "2026-10-09 23:59:59", 3);
        TEdCourseTask y = newTask("S-正常", "2026-10-09 23:59:59", 3);
        service.setHoldDecider(task -> "S-退学".equals(task.getItemNo()) ? "学生已退学" : null);

        EdCourseTaskRound round = service.runOnce(d("2026-10-07 09:00:00"));
        // 挂一条不拖整轮：X 挂起写明原由，Y 照常派出
        assertEquals(1, round.getSuspendedCount());
        assertEquals(1, round.getDispatchedCount());
        assertTrue(round.getErrors().isEmpty());
        TEdCourseTask xAfter = taskMapper.selectById(x.getId());
        assertEquals(2, xAfter.getStatus().intValue());
        assertEquals("学生已退学", xAfter.getHoldReason());
        assertEquals(1, logCount(x.getId(), "SUSPEND"));
        assertEquals(1, statusOf(y.getId()));

        // 挂起的不再参与后续派单
        EdCourseTaskRound next = service.runOnce(d("2026-10-08 09:00:00"));
        assertTrue(next.isEmpty());
        assertEquals(2, statusOf(x.getId()));
        // 幂等：再挂一次落空，不多留痕
        assertFalse(service.suspend(x.getId(), "重复挂起", d("2026-10-08 09:00:00")));
        assertEquals(1, logCount(x.getId(), "SUSPEND"));
    }

    @Test
    void cancelOnlyAfterDeadlineAndNeverWhenDispatched() {
        Date before = d("2026-10-07 09:00:00");
        Date after = d("2026-10-11 00:00:00");
        TEdCourseTask c1 = newTask("C-撤单", "2026-10-10 23:59:59", 3);
        // 截止没过不许撤
        assertThrows(IllegalStateException.class, () -> service.cancel(c1.getId(), "想撤", before));
        // 截止过了还派不出去：撤单留痕
        assertTrue(service.cancel(c1.getId(), "学生退学，撤单", after));
        TEdCourseTask c1After = taskMapper.selectById(c1.getId());
        assertEquals(3, c1After.getStatus().intValue());
        assertEquals("学生退学，撤单", c1After.getHoldReason());
        assertEquals(1, logCount(c1.getId(), "CANCEL"));
        // 最早定的截止日不许被覆盖
        assertEquals(d("2026-10-10 23:59:59").getTime() / 1000, c1After.getDueAt().getTime() / 1000);
        assertNull(c1After.getDispatchedAt());

        // 已派出的不许撤
        TEdCourseTask c2 = newTask("C-已派", "2026-10-10 23:59:59", 3);
        service.runOnce(before);
        assertEquals(1, statusOf(c2.getId()));
        assertThrows(IllegalStateException.class, () -> service.cancel(c2.getId(), "想撤", after));
        // 真派出的时刻不许被覆盖
        TEdCourseTask c2After = taskMapper.selectById(c2.getId());
        assertEquals(before.getTime() / 1000, c2After.getDispatchedAt().getTime() / 1000);

        // 挂起的过了截止可以撤（派不出去的安置）
        TEdCourseTask c3 = newTask("C-挂后撤", "2026-10-10 23:59:59", 3);
        assertTrue(service.suspend(c3.getId(), "辅导员整组调走", before));
        assertTrue(service.cancel(c3.getId(), "截止已过，撤单", after));
        assertEquals(3, statusOf(c3.getId()));
    }

    // ================= 进件与调整 =================

    @Test
    void registerIgnoresReentryAndOnlyAmountIsAdjustable() {
        Date at = d("2026-10-07 09:00:00");
        TEdCourseTask t = freshTask("N-1", "2026-10-10 23:59:59", 1);
        assertTrue(service.register(t));
        // 当天再进来的直接忽略，先前那条原样留着
        assertFalse(service.register(freshTask("N-1", "2026-10-10 23:59:59", 1)));
        assertEquals(1, taskMapper.selectCount(new QueryWrapper<TEdCourseTask>().eq("item_no", "N-1")).intValue());

        // 还剩 3 天 > 提前 1 天：不浮出；调"还剩几天"到 3 才浮出——真能调的只有这一样
        assertTrue(service.listDue(at).isEmpty());
        assertTrue(service.adjustAmount(t.getId(), new BigDecimal("3"), at));
        TEdCourseTask adjusted = taskMapper.selectById(t.getId());
        assertEquals(0, adjusted.getAmount().compareTo(new BigDecimal("3")));
        // 截止那一刻动不得
        assertEquals(d("2026-10-10 23:59:59").getTime() / 1000, adjusted.getDueAt().getTime() / 1000);
        assertEquals(1, service.listDue(at).size());

        // 已派出去的连"还剩几天"也不再调
        service.runOnce(at);
        assertEquals(1, statusOf(t.getId()));
        assertFalse(service.adjustAmount(t.getId(), new BigDecimal("9"), at));
    }

    // ================= 一轮点个数 =================

    @Test
    void emptyRoundIsNormalNotFault() {
        EdCourseTaskRound round = service.runOnce(d("2026-10-07 09:00:00"));
        assertTrue(round.isEmpty());
        assertEquals(0, round.getDispatchedCount());
        assertEquals(0, round.getPendingCount());
        assertEquals(0, round.getSuspendedCount());
        assertTrue(round.getMessage().contains("正常情形"));
    }

    @Test
    void countsAndDetailsComeFromTheSamePass() {
        TEdCourseTask d1 = newTask("M-派", "2026-10-09 23:59:59", 5);
        TEdCourseTask x1 = newTask("M-挂", "2026-10-09 23:59:59", 5);
        TEdCourseTask n1 = newTask("M-未到", "2026-10-20 23:59:59", 1);
        service.setHoldDecider(task -> "M-挂".equals(task.getItemNo()) ? "学生已休学" : null);

        EdCourseTaskRound round = service.runOnce(d("2026-10-07 09:00:00"));
        // 屏上三处数＝明细清单的 size，同一次点出来
        assertEquals(1, round.getDispatchedCount());
        assertEquals(1, round.getSuspendedCount());
        assertEquals(0, round.getPendingCount());
        assertEquals(d1.getId(), round.getDispatched().get(0).getId());
        assertEquals(x1.getId(), round.getSuspended().get(0).getId());
        // 与库里明细两边对的是同一份数
        assertEquals(round.getDispatchedCount(), countByStatus(1));
        assertEquals(round.getSuspendedCount(), countByStatus(2));
        assertEquals(round.getPendingCount(), countByStatus(0) - 1); // 减去本轮没到期的那条
        assertEquals(0, statusOf(n1.getId()));
    }

    // ================= 工具 =================

    private static Date d(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (ParseException e) {
            throw new IllegalArgumentException(s, e);
        }
    }

    private static TEdCourseTask freshTask(String itemNo, String dueAt, long amountDays) {
        TEdCourseTask t = new TEdCourseTask();
        t.setItemNo(itemNo);
        t.setDueAt(d(dueAt));
        t.setAmount(BigDecimal.valueOf(amountDays));
        t.setContent("催办事由-" + itemNo);
        return t;
    }

    private TEdCourseTask newTask(String itemNo, String dueAt, long amountDays) {
        TEdCourseTask t = freshTask(itemNo, dueAt, amountDays);
        t.setStatus(0);
        t.setDelFlag(0);
        t.setCreateTime(d("2026-10-01 09:00:00"));
        taskMapper.insert(t);
        return t;
    }

    private TEdCourseTask newTaskWithStatus(String itemNo, int status) {
        TEdCourseTask t = newTask(itemNo, "2026-10-10 23:59:59", 3);
        t.setStatus(status);
        taskMapper.updateById(t);
        return t;
    }

    private int statusOf(Long id) {
        return taskMapper.selectById(id).getStatus().intValue();
    }

    private int logCount(Long taskId, String action) {
        return logMapper.selectCount(new QueryWrapper<TEdCourseTaskLog>()
                .eq("task_id", taskId).eq("action", action)).intValue();
    }

    private TEdCourseTaskLog logOf(Long taskId, String action) {
        return logMapper.selectOne(new QueryWrapper<TEdCourseTaskLog>()
                .eq("task_id", taskId).eq("action", action));
    }

    private int countByStatus(int status) {
        return taskMapper.selectCount(new QueryWrapper<TEdCourseTask>()
                .eq("status", status).eq("del_flag", 0)).intValue();
    }
}
