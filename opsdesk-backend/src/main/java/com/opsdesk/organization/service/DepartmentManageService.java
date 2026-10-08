package com.opsdesk.organization.service;

import com.opsdesk.organization.dto.DepartmentCreateDTO;
import com.opsdesk.organization.dto.DepartmentUpdateDTO;
import com.opsdesk.organization.vo.DepartmentNodeVO;

import java.util.List;

/**
 * 部门管理业务 Service（工单 D1-04）
 *
 * <p>规格依据：API 文档 §6.1–§6.4、规格基线 §8.3（{@code department.path} 前缀匹配是
 * 数据范围「含子部门递归」的基础）。
 *
 * <p><b>为什么不把方法加在生成的 {@code DepartmentService} 上</b>：
 * {@code tools/gen_entities.py} 会<b>无条件覆盖</b> {@code organization/service/DepartmentService.java}
 * 与 {@code impl/DepartmentServiceImpl.java}（脚本 L359-365 直接 {@code open(path, "w")}），
 * 往生成物里加方法下次重跑脚本就静默丢失。业务方法一律另建 Service（D1-03 起沿用此约定）。
 *
 * <p>⚠️ 本工单<b>先不加权限注解</b>（按工单 §3 的依赖说明）；按 API 文档 §6 抬头，
 * 这一组接口应当限 {@code ADMIN}，等 D2-02 完成后回来补 {@code @RequirePermission("department:*")}。
 */
public interface DepartmentManageService {

    /**
     * 部门树（API 文档 §6.1）。
     *
     * <p>返回<b>数组</b>（可含多个根），同级按 {@code sort} 升序、{@code sort} 相同按 id 升序。
     */
    List<DepartmentNodeVO> tree();

    /**
     * 创建部门（API 文档 §6.2）。
     *
     * <p>服务端在同一事务内自动维护 {@code path}：先 INSERT 拿自增 id，再回填
     * {@code path = 父.path + id + "/"}（根部门为 {@code "/" + id + "/"}）。
     *
     * @return 新部门 ID
     * @throws com.opsdesk.common.BizException 父部门不存在（40001）
     */
    Long create(DepartmentCreateDTO dto);

    /**
     * 修改部门（API 文档 §6.3）。
     *
     * <p>变更 {@code parentId} 时会<b>级联重算整棵子树的 {@code path}</b>。
     *
     * @throws com.opsdesk.common.BizException 部门不存在（40400）；父部门不存在（40001）；
     *                                         移动到自己的子部门下成环（40900）
     */
    void update(Long id, DepartmentUpdateDTO dto);

    /**
     * 删除部门（API 文档 §6.4）—— <b>逻辑删除</b>。
     *
     * @throws com.opsdesk.common.BizException 部门不存在（40400）；存在子部门或部门下有用户（40900）
     */
    void delete(Long id);
}
