package com.evops.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.task.entity.BatchTask;
import org.apache.ibatis.annotations.Select;

public interface BatchTaskMapper extends BaseMapper<BatchTask> {

    /** 任务号全局序列（H2 持久化序列，重启不回退） */
    @Select("SELECT NEXT VALUE FOR seq_batch_task_no")
    long nextTaskNoSeq();
}
