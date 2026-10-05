package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.common.conf.EdCourseTaskProps;
import com.fc.v2.mapper.auto.TEdCourseTaskMapper;
import com.fc.v2.model.auto.TEdCourseTask;
import com.fc.v2.model.custom.EdCourseTaskRound;
import com.fc.v2.service.EdCourseTaskHoldDecider;
import com.fc.v2.service.ITEdCourseTaskService;
import com.fc.v2.util.EdCourseTaskRuler;

/**
 * 选课催办事项条 Service业务层处理（scheduling-job 形状：周期执行）。
 * <p>一轮派单的规矩：</p>
 * <ul>
 *   <li>截止口径全校一套：浮出与回写同走 EdCourseTaskRuler，不各算各的；</li>
 *   <li>派出去＝派发时刻写上＋情形待派翻已派，同一条 UPDATE 落笔，缺一不算成；</li>
 *   <li>这一轮派出去的下一轮不再当待派捞出来；几个触发点撞同一刻也只派一回；</li>
 *   <li>挂一条不拖整轮：单条失败记进本轮 errors，其余照常往下派；</li>
 *   <li>本轮确实没有到期的，直说没活——选课窗口没攒下人，属正常情形。</li>
 * </ul>
 *
 * @author fuce
 * @date 2026-10-04
 */
@Service
public class TEdCourseTaskServiceImpl implements ITEdCourseTaskService {

    @javax.annotation.Resource
    private TEdCourseTaskMapper edCourseTaskMapper;

    @Autowired
    private EdCourseTaskProps edCourseTaskProps;

    @Autowired
    private EdCourseTaskWriter edCourseTaskWriter;

    /** 办不办得成判定口：学籍/编制侧接入时替换实现；未接入默认可派 */
    @Autowired(required = false)
    private EdCourseTaskHoldDecider holdDecider;

    @Override
    public TEdCourseTask selectTEdCourseTaskById(Long id) {
        return this.edCourseTaskMapper.selectById(id);
    }

    @Override
    public List<TEdCourseTask> listDue(Date at) {
        List<TEdCourseTask> waiting = this.edCourseTaskMapper.selectList(new QueryWrapper<TEdCourseTask>()
                .eq("status", EdCourseTaskWriter.ST_WAIT)
                .eq("del_flag", 0)
                .isNotNull("due_at")
                .isNotNull("amount"));
        List<TEdCourseTask> due = new ArrayList<TEdCourseTask>();
        for (TEdCourseTask t : waiting) {
            if (EdCourseTaskRuler.isDue(at, t.getDueAt(), t.getAmount())) {
                due.add(t);
            }
        }
        return due;
    }

    @Override
    public EdCourseTaskRound runOnce(Date at) {
        EdCourseTaskRound round = new EdCourseTaskRound();
        round.setRoundAt(at);
        String slot = this.edCourseTaskProps.slotOf(at);
        round.setSlot(slot);
        if (EdCourseTaskProps.SLOT_NONE.equals(slot)) {
            // 夜间和法定节假日整段不派：窗口外有到期的也放着，等下一轮再动
            round.setWindowOpen(false);
            round.setMessage("窗口外（夜间或法定节假日）整段不派，到期的放着等下一轮");
            return round;
        }
        round.setWindowOpen(true);
        List<TEdCourseTask> due = listDue(at);
        if (due.isEmpty()) {
            round.setEmpty(true);
            round.setMessage("本轮没有到期的提醒——选课窗口没攒下人，属正常情形");
            return round;
        }
        // 超三天没催出去的顶到最前头；普通提醒按浮出先后排队，不许插队
        final Date now = at;
        final int agedDays = this.edCourseTaskProps.getAgedDays();
        due.sort(Comparator
                .comparing((TEdCourseTask t) -> EdCourseTaskRuler.surfacedDays(now, t.getDueAt(), t.getAmount()) > agedDays ? 0 : 1)
                .thenComparing(t -> EdCourseTaskRuler.surfaceAt(t.getDueAt(), t.getAmount()))
                .thenComparing(TEdCourseTask::getId));
        Map<String, Long> load = countInProgressByCounselor();
        for (TEdCourseTask task : due) {
            try {
                String holdReason = this.holdDecider == null ? null : this.holdDecider.holdReason(task);
                if (holdReason != null) {
                    // 办不成的就地挂起写明原由，其余照常往下派
                    if (this.edCourseTaskWriter.suspend(task, holdReason, now)) {
                        task.setStatus(EdCourseTaskWriter.ST_HELD);
                        task.setHoldReason(holdReason);
                        round.getSuspended().add(task);
                    }
                    continue;
                }
                String counselor = pickCounselor(load);
                if (counselor == null) {
                    // 在册辅导员为空，一条都派不出去，留待下一轮
                    round.getPending().add(task);
                    continue;
                }
                if (this.edCourseTaskWriter.dispatch(task, counselor, now, slot)) {
                    load.merge(counselor, 1L, Long::sum);
                    task.setCounselor(counselor);
                    task.setDispatchedAt(now);
                    task.setStatus(EdCourseTaskWriter.ST_SENT);
                    round.getDispatched().add(task);
                }
                // 没抢到的＝同一刻别的触发点已派走，那一轮已计，本轮不重复计
            } catch (Exception e) {
                round.getErrors().add(task.getItemNo() + ": " + e.getMessage());
            }
        }
        round.setMessage(String.format("本轮派 %d 条、挂起 %d 条、余 %d 条待派",
                round.getDispatchedCount(), round.getSuspendedCount(), round.getPendingCount()));
        return round;
    }

    @Override
    public boolean register(TEdCourseTask task) {
        if (task == null || task.getItemNo() == null || task.getDueAt() == null || task.getAmount() == null) {
            return false;
        }
        // 同事项号已落过条的（含已派/挂起/已撤），再进来直接忽略，先前那条原样留着
        Integer exists = this.edCourseTaskMapper.selectCount(new QueryWrapper<TEdCourseTask>()
                .eq("item_no", task.getItemNo()));
        if (exists != null && exists.intValue() > 0) {
            return false;
        }
        task.setId(null);
        task.setStatus(EdCourseTaskWriter.ST_WAIT);
        task.setDelFlag(0);
        task.setCreateTime(new Date());
        try {
            return this.edCourseTaskMapper.insert(task) > 0;
        } catch (RuntimeException e) {
            // 并发进同一条：唯一键兜底，只留一条
            if (EdCourseTaskWriter.isDuplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public boolean suspend(Long id, String reason, Date at) {
        TEdCourseTask task = this.edCourseTaskMapper.selectById(id);
        if (task == null || task.getDelFlag() == null || task.getDelFlag().intValue() != 0) {
            return false;
        }
        return this.edCourseTaskWriter.suspend(task, reason, at);
    }

    @Override
    public boolean cancel(Long id, String reason, Date at) {
        TEdCourseTask task = this.edCourseTaskMapper.selectById(id);
        if (task == null || task.getDelFlag() == null || task.getDelFlag().intValue() != 0) {
            return false;
        }
        if (task.getStatus() != null && task.getStatus().intValue() == EdCourseTaskWriter.ST_SENT) {
            throw new IllegalStateException("已派出的提醒不许撤单：" + task.getItemNo());
        }
        if (!EdCourseTaskRuler.isPastDue(at, task.getDueAt())) {
            throw new IllegalStateException("截止没过不许撤单：" + task.getItemNo());
        }
        return this.edCourseTaskWriter.cancel(task, reason, at);
    }

    @Override
    public boolean adjustAmount(Long id, BigDecimal newAmount, Date at) {
        if (id == null || newAmount == null || newAmount.signum() < 0) {
            return false;
        }
        // 真要调只许调"还剩几天"，且只在待派时调；due_at 全代码没有第二条 UPDATE 路径
        return this.edCourseTaskMapper.update(null, new UpdateWrapper<TEdCourseTask>()
                .eq("id", id)
                .eq("status", EdCourseTaskWriter.ST_WAIT)
                .eq("del_flag", 0)
                .set("amount", newAmount)
                .set("update_time", at)) > 0;
    }

    /**
     * 派给谁：在催越多权重越低，手头空的先接；同负载按在册名单先后，不许插队。
     *
     * @return 受派辅导员；在册名单为空返回 null
     */
    private String pickCounselor(Map<String, Long> load) {
        String best = null;
        long bestLoad = Long.MAX_VALUE;
        for (String counselor : this.edCourseTaskProps.getCounselors()) {
            Long cur = load.get(counselor);
            long l = cur == null ? 0L : cur.longValue();
            if (l < bestLoad) {
                bestLoad = l;
                best = counselor;
            }
        }
        return best;
    }

    /** 各辅导员手头在催（已派未结）的人数。 */
    private Map<String, Long> countInProgressByCounselor() {
        QueryWrapper<TEdCourseTask> qw = new QueryWrapper<TEdCourseTask>()
                .select("counselor AS counselor", "COUNT(*) AS cnt")
                .eq("status", EdCourseTaskWriter.ST_SENT)
                .eq("del_flag", 0)
                .isNotNull("counselor")
                .groupBy("counselor");
        Map<String, Long> load = new HashMap<String, Long>();
        for (Map<String, Object> row : this.edCourseTaskMapper.selectMaps(qw)) {
            String counselor = strVal(row, "counselor");
            Object cnt = objVal(row, "cnt");
            if (counselor != null && cnt instanceof Number) {
                load.put(counselor, Long.valueOf(((Number) cnt).longValue()));
            }
        }
        return load;
    }

    /** selectMaps 的列名大小写随库走（MySQL 小写、H2 大写），取值统一忽略大小写。 */
    private static Object objVal(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String strVal(Map<String, Object> row, String key) {
        Object v = objVal(row, key);
        return v == null ? null : String.valueOf(v);
    }

    /* —— 以下为测试与装配用的 setter —— */

    public void setEdCourseTaskMapper(TEdCourseTaskMapper edCourseTaskMapper) {
        this.edCourseTaskMapper = edCourseTaskMapper;
    }

    public void setEdCourseTaskProps(EdCourseTaskProps edCourseTaskProps) {
        this.edCourseTaskProps = edCourseTaskProps;
    }

    public void setEdCourseTaskWriter(EdCourseTaskWriter edCourseTaskWriter) {
        this.edCourseTaskWriter = edCourseTaskWriter;
    }

    public void setHoldDecider(EdCourseTaskHoldDecider holdDecider) {
        this.holdDecider = holdDecider;
    }
}
