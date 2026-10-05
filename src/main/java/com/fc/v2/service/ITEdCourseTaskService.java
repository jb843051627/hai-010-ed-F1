package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TEdCourseTask;
import com.fc.v2.model.custom.EdCourseTaskRound;

/**
 * 选课催办事项条 业务层接口（scheduling-job 形状：周期执行）。
 * <p>一条提醒记五样：提醒号(item_no)、截止那一刻(due_at)、还剩几天该浮出(amount)、
 * 当前情形(status)、事由与负责辅导员(content/counselor)。</p>
 *
 * @author fuce
 * @date 2026-10-04
 */
public interface ITEdCourseTaskService {

    /** selectTEdCourseTaskById */
    TEdCourseTask selectTEdCourseTaskById(Long id);

    /**
     * 这一刻到期该浮出的待派提醒。判定只走 EdCourseTaskRuler 一把尺子，
     * 与派单回写同口径，不各算各的。
     */
    List<TEdCourseTask> listDue(Date at);

    /**
     * 跑一轮派单。窗口外整轮不动；没有到期的直说没活（正常情形）；
     * 到期的按"超三天没催出去的顶最前、其余按浮出先后"排队，
     * 按辅导员手头在催人数派（在催越多权重越低，手头空的先接）。
     * 返回的三处数与明细是同一次点出来的同一份。
     */
    EdCourseTaskRound runOnce(Date at);

    /**
     * 进一条提醒。同一档里同一条提醒只派一次：事项号已落过条的，
     * 当天再进来直接忽略，先前那条原样留着。
     *
     * @return true＝新落了一条；false＝重复进件被忽略
     */
    boolean register(TEdCourseTask task);

    /**
     * 就地挂起写明原由（学生退学休学、辅导员整组调走等）。
     * 挂起的不再参与后续派单；已不在待派情形的调用落空，幂等。
     */
    boolean suspend(Long id, String reason, Date at);

    /**
     * 撤单（或改用途后的旧条安置）。只许对"截止过了还派不出去"的条子撤；
     * 撤单留痕，最早定的截止日与真派出的时刻不许被覆盖。
     */
    boolean cancel(Long id, String reason, Date at);

    /**
     * 真要调只许调"还剩几天"（amount），且只在待派时调；
     * 截止那一刻（due_at）任何路径都动不得。
     */
    boolean adjustAmount(Long id, BigDecimal newAmount, Date at);
}
