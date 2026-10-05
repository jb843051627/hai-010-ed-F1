package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TEdCourseTask;

/**
 * 一轮派单的结果。屏上三个数（待派/已派/挂起）与明细清单是同一次点出来的：
 * 数就是清单的 size，两边对的是同一份，不许各算各的。
 *
 * @author fuce
 * @date 2026-10-04
 */
public class EdCourseTaskRound implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 这一轮的时刻 */
    private Date roundAt;

    /** 落在哪一档 DAY日常 PEAK高峰 NONE窗口外 */
    private String slot;

    /** 窗口是否开着（false＝夜间或法定节假日，本轮不动） */
    private boolean windowOpen;

    /** 本轮是否没有到期的提醒（选课窗口没攒下人，属正常情形） */
    private boolean empty;

    /** 本轮派出去的明细 */
    private List<TEdCourseTask> dispatched = new ArrayList<TEdCourseTask>();

    /** 本轮到期但仍待派的明细（如在册辅导员为空，一条都没派出去） */
    private List<TEdCourseTask> pending = new ArrayList<TEdCourseTask>();

    /** 本轮就地挂起的明细 */
    private List<TEdCourseTask> suspended = new ArrayList<TEdCourseTask>();

    /** 本轮单条处理失败的消息（挂一条不拖整轮，记在这里） */
    private List<String> errors = new ArrayList<String>();

    /** 一句话说清这一轮 */
    private String message;

    public int getDispatchedCount() {
        return this.dispatched.size();
    }

    public int getPendingCount() {
        return this.pending.size();
    }

    public int getSuspendedCount() {
        return this.suspended.size();
    }

    public Date getRoundAt() {
        return roundAt;
    }

    public void setRoundAt(Date roundAt) {
        this.roundAt = roundAt;
    }

    public String getSlot() {
        return slot;
    }

    public void setSlot(String slot) {
        this.slot = slot;
    }

    public boolean isWindowOpen() {
        return windowOpen;
    }

    public void setWindowOpen(boolean windowOpen) {
        this.windowOpen = windowOpen;
    }

    public boolean isEmpty() {
        return empty;
    }

    public void setEmpty(boolean empty) {
        this.empty = empty;
    }

    public List<TEdCourseTask> getDispatched() {
        return dispatched;
    }

    public void setDispatched(List<TEdCourseTask> dispatched) {
        this.dispatched = dispatched;
    }

    public List<TEdCourseTask> getPending() {
        return pending;
    }

    public void setPending(List<TEdCourseTask> pending) {
        this.pending = pending;
    }

    public List<TEdCourseTask> getSuspended() {
        return suspended;
    }

    public void setSuspended(List<TEdCourseTask> suspended) {
        this.suspended = suspended;
    }

    public List<String> getErrors() {
        return errors;
    }

    public void setErrors(List<String> errors) {
        this.errors = errors;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
