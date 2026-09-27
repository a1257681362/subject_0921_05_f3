package com.evops.lens.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.evops.lens.dto.LensSaveRequest;
import com.evops.lens.dto.LensUpdateRequest;
import com.evops.lens.entity.Lens;
import com.evops.lens.entity.LensOperLog;
import com.evops.lens.mapper.LensMapper;
import com.evops.lens.mapper.LensOperLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 镜片基础信息：建档 / 修改 / 作废 / 恢复，全程写操作日志；禁止物理删除。
 */
@Service
public class LensService {

    public static final String ACTIVE = "ACTIVE";
    public static final String DISCARDED = "DISCARDED";

    @Resource
    private LensMapper lensMapper;
    @Resource
    private LensOperLogMapper operLogMapper;

    @Transactional(rollbackFor = Exception.class)
    public Lens create(LensSaveRequest req) {
        Lens lens = new Lens();
        lens.setLensCode(req.getLensCode());
        lens.setLensName(req.getLensName());
        lens.setBrand(req.getBrand());
        lens.setLensType(req.getLensType());
        lens.setRefractiveIndex(req.getRefractiveIndex());
        lens.setPrice(req.getPrice());
        lens.setRemark(req.getRemark());
        lens.setLensStatus(ACTIVE);
        // 唯一约束 uk_lens_code 兜底并发重复建档
        lensMapper.insert(lens);
        lens = lensMapper.selectById(lens.getId());
        writeLog(lens.getId(), "CREATE", "建档：" + lens.getLensName(), lens.getVersionNo());
        return lens;
    }

    @Transactional(rollbackFor = Exception.class)
    public Lens update(LensUpdateRequest req) {
        Lens lens = requireLens(req.getId());
        if (DISCARDED.equals(lens.getLensStatus())) {
            throw new BizException(BizCode.LENS_NOT_ACTIVE, "已作废镜片不可修改，请先恢复");
        }
        lens.setVersionNo(req.getVersionNo()); // 乐观锁条件
        if (req.getLensName() != null) lens.setLensName(req.getLensName());
        if (req.getBrand() != null) lens.setBrand(req.getBrand());
        if (req.getLensType() != null) lens.setLensType(req.getLensType());
        if (req.getRefractiveIndex() != null) lens.setRefractiveIndex(req.getRefractiveIndex());
        if (req.getPrice() != null) lens.setPrice(req.getPrice());
        if (req.getRemark() != null) lens.setRemark(req.getRemark());

        int rows = lensMapper.updateById(lens); // MP 乐观锁自动追加 version_no 条件
        if (rows == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        lens = lensMapper.selectById(lens.getId());
        writeLog(lens.getId(), "UPDATE", "修改镜片信息", lens.getVersionNo());
        return lens;
    }

    @Transactional(rollbackFor = Exception.class)
    public void discard(Long id, String remark) {
        Lens lens = requireLens(id);
        if (DISCARDED.equals(lens.getLensStatus())) {
            throw new BizException(BizCode.LENS_ALREADY_DISCARDED);
        }
        lens.setLensStatus(DISCARDED);
        if (lensMapper.updateById(lens) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        lens = lensMapper.selectById(lens.getId());
        writeLog(id, "DISCARD", remark, lens.getVersionNo());
    }

    @Transactional(rollbackFor = Exception.class)
    public void restore(Long id, String remark) {
        Lens lens = requireLens(id);
        if (ACTIVE.equals(lens.getLensStatus())) {
            throw new BizException(BizCode.LENS_NOT_DISCARDED);
        }
        lens.setLensStatus(ACTIVE);
        if (lensMapper.updateById(lens) == 0) {
            throw new BizException(BizCode.VERSION_CONFLICT);
        }
        lens = lensMapper.selectById(lens.getId());
        writeLog(id, "RESTORE", remark, lens.getVersionNo());
    }

    public Lens get(Long id) {
        return requireLens(id);
    }

    public Page<Lens> page(long current, long size, String status, String keyword) {
        LambdaQueryWrapper<Lens> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(Lens::getLensStatus, status.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            qw.and(w -> w.like(Lens::getLensCode, keyword.trim())
                    .or().like(Lens::getLensName, keyword.trim()));
        }
        qw.orderByDesc(Lens::getId);
        return lensMapper.selectPage(new Page<>(current, size), qw);
    }

    private Lens requireLens(Long id) {
        Lens lens = lensMapper.selectById(id);
        if (lens == null) {
            throw new BizException(BizCode.LENS_NOT_FOUND);
        }
        return lens;
    }

    private void writeLog(Long lensId, String operType, String remark, Long versionNo) {
        LensOperLog log = new LensOperLog();
        log.setLensId(lensId);
        log.setOperType(operType);
        log.setOperRemark(remark);
        log.setOperBy(RequestContext.operatorId());
        log.setRequestNo(RequestContext.requestNo());
        log.setBizTime(RequestContext.bizTime());
        log.setVersionNo(versionNo);
        operLogMapper.insert(log);
    }
}
