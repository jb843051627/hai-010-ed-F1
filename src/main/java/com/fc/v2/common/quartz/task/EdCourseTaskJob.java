package com.fc.v2.common.quartz.task;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fc.v2.model.custom.EdCourseTaskRound;
import com.fc.v2.service.ITEdCourseTaskService;

/**
 * 选课催办派单定时入口。在 quartz 任务管理里配
 * {@code edCourseTaskJob.runOnce()} 即按周期跑轮。
 *
 * @author fuce
 * @date 2026-10-04
 */
@Component("edCourseTaskJob")
public class EdCourseTaskJob {

    private static final Logger log = LoggerFactory.getLogger(EdCourseTaskJob.class);

    @Autowired
    private ITEdCourseTaskService edCourseTaskService;

    /**
     * 跑一轮派单。窗口外、没到期都属正常情形，只记一行日志，不告警。
     */
    public void runOnce() {
        EdCourseTaskRound round = this.edCourseTaskService.runOnce(new Date());
        log.info("选课催办本轮（档={}）：{}", round.getSlot(), round.getMessage());
        if (!round.getErrors().isEmpty()) {
            log.warn("选课催办本轮单条失败（不拖整轮）：{}", round.getErrors());
        }
    }
}
