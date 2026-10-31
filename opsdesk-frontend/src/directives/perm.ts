/**
 * `v-perm` —— 按钮级权限指令。
 *
 * 用法：
 * ```vue
 * <el-button v-perm="'ticket:assign'">分派</el-button>
 * <el-button v-perm="['ticket:assign', 'ticket:transfer']">转移</el-button>
 * ```
 *
 * 语义：
 * - 字符串：必须持有该权限码
 * - 数组：**任一满足**即可（OR），不是全满足
 * - 不传值 / 空数组：不做限制（当成「没写」而不是「全部拒绝」，避免误伤）
 *
 * 无权限时**真正移除元素**（`removeChild`），不是 `display:none` ——
 * 藏起来但留在 DOM 里的按钮，既骗不过接口，也容易被「右键检查」发现而显得业余。
 *
 * ⚠️ 再次强调：**这只是界面显隐，不是安全边界。**
 * 权限码来自 `GET /api/auth/me`，用户能在浏览器里改掉它，
 * 但后端每个接口都会独立校验，改前端权限拿不到任何越权数据。
 *
 * 为什么不需要 `updated` 钩子：权限只在启动时从 `/me` 拉一次，
 * 运行期不会变（改角色要刷新才生效），所以挂载时判一次就够。
 */

import type { Directive } from 'vue'

import { useUserStore } from '@/stores/user'

/** 指令值：单个权限码或权限码数组 */
export type PermValue = string | string[]

/** 判断是否有权 */
function isAllowed(value: PermValue): boolean {
  const codes = Array.isArray(value) ? value : [value]
  if (codes.length === 0) return true

  const userStore = useUserStore()
  return codes.some((code) => userStore.hasPerm(code))
}

export const perm: Directive<HTMLElement, PermValue> = {
  mounted(el, binding) {
    // 没写值 = 没约束，不要因为「忘了传参」把按钮全删了
    if (!binding.value) return

    if (!isAllowed(binding.value)) {
      el.parentNode?.removeChild(el)
    }
  },
}
