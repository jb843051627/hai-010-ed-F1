package com.fc.v2.service.impl;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fc.v2.mapper.auto.TEdCourseTaskLogMapper;
import com.fc.v2.mapper.auto.TEdCourseTaskMapper;
import com.fc.v2.model.auto.TEdCourseTask;
import com.fc.v2.model.auto.TEdCourseTaskLog;

/**
 * 选课催办状态落笔口（每条提醒一个独立事务，挂一条不拖整轮）。
 * <p>三种终态迁移都走"条件更新"：WHERE 里卡死原情形，几个触发点撞在同一刻
 * 也只有一个能改中（影响行数=1），其余原样落空——派一次只派一回。
 * 落笔后紧接着写留痕表，留痕表 uk(task_id, action) 兜底：同一动作只留一条。</p>
 *
 * @author fuce
 * @date 2026-10-04
 */
@Component
public class EdCourseTaskWriter {

    private static final Logger log = LoggerFactory.getLogger(EdCourseTaskWriter.class);

    /** 条目情形 0待派 1已派 2挂起 3已撤 */
    public static final int ST_WAIT = 0;
    public static final int ST_SENT = 1;
    public static final int ST_HELD = 2;
    public static final int ST_CANCELLED = 3;

    /** 留痕动作 */
    public static final String ACT_DISPATCH = "DISPATCH";
    public static final String ACT_SUSPEND = "SUSPEND";
    public static final String ACT_CANCEL = "CANCEL";

    @javax.annotation.Resource
    private TEdCourseTaskMapper edCourseTaskMapper;

    @javax.annotation.Resource
    private TEdCourseTaskLogMapper edCourseTaskLogMapper;

    /**
     * 待派 → 已派：派发时刻与情形翻转在同一条 UPDATE 里落笔，缺一不算成。
     *
     * @return true＝本轮抢到了这条；false＝已被别的触发点派走（幂等，不再派第二条）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean dispatch(TEdCourseTask task, String counselor, Date at, String slot) {
        int rows = this.edCourseTaskMapper.update(null, new UpdateWrapper<TEdCourseTask>()
                .eq("id", task.getId())
                .eq("status", ST_WAIT)
                .eq("del_flag", 0)
                .set("status", ST_SENT)
                .set("counselor", counselor)
                .set("dispatched_at", at)
                .set("update_time", at));
        if (rows == 0) {
            return false;
        }
        trace(task, ACT_DISPATCH, slot, counselor, null, at);
        return true;
    }

    /**
     * 待派 → 挂起：写明原由，挂起的不再参与后续派单。
     *
     * @return true＝本轮挂上了；false＝这条已不在待派（已派/已挂/已撤），不动它
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean suspend(TEdCourseTask task, String reason, Date at) {
        int rows = this.edCourseTaskMapper.update(null, new UpdateWrapper<TEdCourseTask>()
                .eq("id", task.getId())
                .eq("status", ST_WAIT)
                .eq("del_flag", 0)
                .set("status", ST_HELD)
                .set("hold_reason", reason)
                .set("update_time", at));
        if (rows == 0) {
            return false;
        }
        trace(task, ACT_SUSPEND, null, null, reason, at);
        return true;
    }

    /**
     * 待派/挂起 → 已撤：截止过了还派不出去的安置。撤单留痕；
     * 最早定的截止日与真派出的时刻都不在这条 UPDATE 里，谁也覆盖不了。
     *
     * @return true＝撤成了；false＝这条已不在可撤情形
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean cancel(TEdCourseTask task, String reason, Date at) {
        int rows = this.edCourseTaskMapper.update(null, new UpdateWrapper<TEdCourseTask>()
                .eq("id", task.getId())
                .in("status", ST_WAIT, ST_HELD)
                .eq("del_flag", 0)
                .set("status", ST_CANCELLED)
                .set("hold_reason", reason)
                .set("update_time", at));
        if (rows == 0) {
            return false;
        }
        trace(task, ACT_CANCEL, null, null, reason, at);
        return true;
    }

    /** 写一条留痕；同一条同一动作已留过的（唯一键兜底），不重复留。 */
    private void trace(TEdCourseTask task, String action, String slot, String counselor, String detail, Date at) {
        TEdCourseTaskLog rec = new TEdCourseTaskLog();
        rec.setTaskId(task.getId());
        rec.setItemNo(task.getItemNo());
        rec.setAction(action);
        rec.setSlot(slot);
        rec.setCounselor(counselor);
        rec.setDetail(detail);
        rec.setCreateTime(at);
        try {
            this.edCourseTaskLogMapper.insert(rec);
        } catch (RuntimeException e) {
            if (isDuplicateKey(e)) {
                log.warn("催办留痕已存在，不再重复落：taskId={} action={}", task.getId(), action);
                return;
            }
            throw e;
        }
    }

    /** 唯一键冲突判定：Spring 体系抛 DuplicateKeyException，裸 MyBatis 体系是 PersistenceException 包着 SQL 异常，两家都认。 */
    static boolean isDuplicateKey(Throwable e) {
        while (e != null) {
            if (e instanceof org.springframework.dao.DuplicateKeyException
                    || e instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate entry") || msg.contains("Unique index"))) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    /* —— 以下为测试与装配用的 setter —— */

    public void setEdCourseTaskMapper(TEdCourseTaskMapper edCourseTaskMapper) {
        this.edCourseTaskMapper = edCourseTaskMapper;
    }

    public void setEdCourseTaskLogMapper(TEdCourseTaskLogMapper edCourseTaskLogMapper) {
        this.edCourseTaskLogMapper = edCourseTaskLogMapper;
    }
}
