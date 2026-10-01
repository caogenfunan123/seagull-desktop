package com.seagull.carlauncher;

import android.hardware.display.DisplayManager;

/**
 * TRUSTED 虚拟屏 flag 候选与校验 —— 对标用户仓库 carlink-desktop-private 的
 * svc/TrustedDisplayFlags.kt（其上又逐行亲核 OneStep4 的 RootVirtualDisplayFlags）。
 *
 * 为什么这是批次 K 的核心：Android 14 的 ActivityStarter 只允许 home-affinity /
 * TASK_ON_HOME 任务（网易云这类 singleTask 目标）落在【受信】虚拟屏上；
 * MediaProjection 屏（无 TRUSTED）上即使 startActivity 注入了 launchDisplayId，
 * 任务也会被系统拉回默认屏 —— 这正是用户实测"进去桌面还不是画中画界面"的根因。
 *
 * 受信前提：本包持有 android.app.role.COMPANION_DEVICE_APP_STREAMING 角色
 * （root `cmd role add-role-holder` 授予，见 RootOps.grantTrustedDisplayRole），
 * RoleController 会给角色持有者授 ADD_TRUSTED_DISPLAY 签名级权限，
 * 之后本进程即可用 VIRTUAL_DISPLAY_FLAG_TRUSTED 建屏。
 *
 * 隐藏 flag 位（DisplayManager 未公开，值来自 OneStep4 与 AOSP DisplayManager）：
 *   SUPPORTS_TOUCH=1<<6、ROTATES_WITH_CONTENT=1<<7、SHOULD_SHOW_SYSTEM_DECORATIONS=1<<9、
 *   TRUSTED=1<<10、OWN_FOCUS=1<<14、CAN_SHOW_WITH_INSECURE_KEYGUARD=1<<5；
 *   Display 侧校验位 TRUSTED=1<<7（VirtualDisplay.getDisplay().getFlags() 里看）。
 */
public final class TrustedFlags {

    public static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    public static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    public static final int FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    public static final int FLAG_TRUSTED = 1 << 10;
    public static final int FLAG_OWN_FOCUS = 1 << 14;
    public static final int FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 << 5;
    public static final int DISPLAY_FLAG_TRUSTED = 1 << 7;

    private static final int SDK_TRUSTED_MIN = 30;

    private TrustedFlags() {}

    public static boolean supportsTrusted(int sdkInt) { return sdkInt >= SDK_TRUSTED_MIN; }

    /**
     * 规范化（照抄 OneStep4 RootVirtualDisplayFlags.forRootBridge）：
     *  - SDK≥30 保留 TRUSTED，更低剥掉；
     *  - 有 PUBLIC 强制 OWN_CONTENT_ONLY（防空屏镜像默认屏）、剥 INSECURE_KEYGUARD
     *    （Android 14 拒绝二者组合）、永远剥 SECURE。
     */
    public static int normalize(int requested, int sdkInt) {
        int flags = requested;
        if (supportsTrusted(sdkInt)) {
            flags |= FLAG_TRUSTED;
        } else {
            flags &= ~FLAG_TRUSTED;
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
            flags |= DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            flags &= ~FLAG_CAN_SHOW_WITH_INSECURE_KEYGUARD;
        }
        flags &= ~DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE;
        return flags;
    }

    /** 建屏后校验实际 Display flags 是否带 TRUSTED 位（displayFlags=-1 视为失败）。 */
    public static boolean hasTrusted(int displayFlags) {
        return displayFlags >= 0 && (displayFlags & DISPLAY_FLAG_TRUSTED) != 0;
    }

    /** MediaProjection 兜底路径的公开 flag 组合（参考实现 0.2-m7 起实测跑得通）。 */
    public static int projectionFallbackFlags() {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    }

    /** 把 flag 值摊成人话，日志/自检用。 */
    public static String describe(int flags) {
        StringBuilder sb = new StringBuilder("0x").append(Integer.toHexString(flags)).append('[');
        boolean first = true;
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
            sb.append("PUBLIC"); first = false;
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY) != 0) {
            sb.append(first ? "" : '|').append("OWN_CONTENT_ONLY"); first = false;
        }
        if ((flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) {
            sb.append(first ? "" : '|').append("PRESENTATION"); first = false;
        }
        if ((flags & FLAG_SUPPORTS_TOUCH) != 0) {
            sb.append(first ? "" : '|').append("SUPPORTS_TOUCH"); first = false;
        }
        if ((flags & FLAG_ROTATES_WITH_CONTENT) != 0) {
            sb.append(first ? "" : '|').append("ROTATES_WITH_CONTENT");
        }
        if ((flags & FLAG_TRUSTED) != 0) {
            sb.append(first ? "" : '|').append("TRUSTED");
        }
        return sb.append(']').toString();
    }

    /**
     * flag 候选（按 OneStep4 优先序，从最全到最保守）。
     * 每组先过 normalize，建屏后用 hasTrusted 校验，失败换下一组。
     */
    public static int[] candidateFlags() {
        int pub = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
        int own = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        int present = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
        return new int[] {
                // 1: 全量首选（含内容随转 + 自持焦点）
                pub | own | present | FLAG_SUPPORTS_TOUCH | FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                        | FLAG_TRUSTED | FLAG_OWN_FOCUS | FLAG_ROTATES_WITH_CONTENT,
                // 2: 去 ROTATES_WITH_CONTENT
                pub | own | present | FLAG_SUPPORTS_TOUCH | FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                        | FLAG_TRUSTED | FLAG_OWN_FOCUS,
                // 3: 去 OWN_FOCUS
                pub | own | present | FLAG_SUPPORTS_TOUCH | FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
                        | FLAG_TRUSTED,
                // 4: 去 SYSTEM_DECORATIONS（部分 ROM 拒绝非系统应用带它）
                pub | own | present | FLAG_SUPPORTS_TOUCH | FLAG_TRUSTED,
                // 5: 最小 TRUSTED 组合
                pub | own | present | FLAG_TRUSTED,
        };
    }
}
