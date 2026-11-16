package com.letslearnenglish.minecraftplugin.util;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 单文件落盘工具：写同目录临时文件 → 原子替换 → 失败时目标文件保持原样。
 *
 * <h2>为什么需要它（同一个缺陷的三批落点）</h2>
 * <p>Bukkit 的 {@code FileConfiguration.save(File)}（字节码：{@code createParentDirs} →
 * {@code saveToString()} → {@code new FileOutputStream(file)} 覆写）会<b>直接覆写目标文件</b>。
 * 写到一半失败（磁盘满 / 进程被杀 / I/O 错误）就在磁盘上留下<b>半截 YAML</b>：
 * {@code YamlConfiguration.loadConfiguration} 吞掉解析异常返回空配置，下次启动时
 * 整份数据「消失」。对语言文件是「玩家全部掉回默认语言」，对词库是「这个难度突然没了」，
 * 对个人场景是「这个玩家的场景全丢」。
 *
 * <p>第一批（{@code ConfigManager.migrateMessageConfig} / {@code saveConfig}）已在
 * {@code ConfigManager} 内部解决；本类把那套实现提出来，供剩余三处落点
 * （{@code PersonalScenarioStore} 的保存/删除、{@code LetsLearnEnglish.savePlayerLanguages}）
 * 复用，并让 {@code ConfigManager} 反向委托 —— <b>全仓只有一份原子写实现，不会分叉</b>。
 *
 * <h2>契约</h2>
 * <ul>
 *   <li><b>父目录</b>：{@code Files.writeString} 不像 {@code FileConfiguration.save} 那样建父目录，
 *       因此本类<b>自己</b>补 {@link Files#createDirectories}（建不出来即返回 false）。</li>
 *   <li><b>成功</b>：目标文件内容与传入的 {@code content} 逐字节一致，且不留 {@code .tmp}。</li>
 *   <li><b>失败</b>：返回 {@code false}，<b>目标文件一个字节都不动</b>（原有的旧数据仍是可读的
 *       「旧版本」，而不是「坏文件」），并尽力清理 {@code .tmp}。</li>
 *   <li><b>临时文件名固定为 {@code <target>.tmp}</b>：调用方约定全部在 Bukkit 主线程同步落盘
 *       （本类三个调用点都是主线程：聊天导入 / 背包点击删除 / 停用落盘）。UUID 后缀能避免
 *       {@code .tmp} 互撞，却<b>不能</b>修复「同一目标并发写」本身的 last-writer-wins 语义，
 *       属于过度设计；固定名还能让残留文件可被运维直接辨认与手工清理。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * <p>本类无状态，可被任意线程调用；但同一 {@code target} 的并发写不在契约内（见上）。
 */
public final class AtomicFileWriter {

    /** 临时文件后缀。测试与运维都按「目标路径 + 该后缀」定位残留文件。 */
    public static final String TMP_SUFFIX = ".tmp";

    private AtomicFileWriter() {
    }

    /**
     * 原子写：临时文件清理失败时的诊断走传入的 {@code logger}。
     *
     * @param target  目标文件（父目录不存在会被创建）
     * @param content 文件内容（在 try 内求值 —— 序列化异常与写盘异常同一处理路径）
     * @param logger  失败日志落点
     * @return {@code true} 已落盘；{@code false} 失败且目标文件保持原样
     */
    public static boolean write(Path target, Supplier<String> content, Logger logger) {
        return write(target, content, logger, tmp -> deleteQuietly(tmp, logger));
    }

    /**
     * 原子写，并允许调用方自定义「清理临时文件失败」的诊断落点。
     *
     * <p><b>为什么留这个缝</b>：{@code ConfigManager} 的清理失败诊断挂在
     * {@code Logger.getLogger(ConfigManager.class.getName())} 上，而不是插件的 logger ——
     * 那是既有测试（以及运维按类名过滤日志的习惯）依赖的契约。把诊断固定成本类的 logger
     * 会让那条契约静默失效，因此这里不假设日志落点，由调用方注入。
     *
     * @param tmpCleanup 清理 {@code .tmp} 的策略；正常路径下失败时被调用（成功路径不调用）
     */
    public static boolean write(Path target, Supplier<String> content, Logger logger,
                                Consumer<Path> tmpCleanup) {
        Path absolute = target.toAbsolutePath();
        if (!ensureParentDirectory(absolute, logger)) {
            return false;
        }

        Path tmp = Path.of(absolute + TMP_SUFFIX);
        try {
            Files.writeString(tmp, content.get(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // content.get() 的序列化异常（如 YAML 里有不可序列化的对象）也落在这里：
            // 它与写盘失败对调用方是同一件事 —— 没写出去，目标保持原样。
            logger.log(Level.SEVERE, "Failed to write temporary config file " + tmp, e);
            tmpCleanup.accept(tmp);
            return false;
        }

        return moveIntoPlace(tmp, absolute, logger, tmpCleanup);
    }

    /**
     * 二进制/流式原子写：临时文件里逐块写字节，全部写成功后才 rename 到目标。
     *
     * <h2>为什么需要这个重载（而不是复用 {@link #write(Path, Supplier, Logger)}）</h2>
     * <p>{@code write} 走 {@code Files.writeString(..., UTF_8)} —— 对<b>二进制</b>负载是破坏性的：
     * 任意字节序列先转 String 再按 UTF-8 编码，往返不再逐字节相等，WAV 会被写坏。
     * 加一个 {@code Supplier<byte[]>} 重载能绕过这个，但要求调用方<b>先把整份内容攒进内存</b>。
     *
     * <h2>为什么是流式而不是「攒成 byte[]」</h2>
     * <p>TTS 的负载是网络流式下载的音频（44kHz 16bit 立体声 ≈ 172 KB/s）。攒进内存意味着
     * 峰值内存与文件大小线性相关，且要自己处理 {@code ByteArrayOutputStream} 扩容 ——
     * 大文件下这是可避免的 OOM 面。而流式写临时文件 + rename <b>同样是原子的</b>，
     * 峰值内存恒定为调用方的 buffer 大小（TTS 用 4KB）。因此没有理由走攒内存那条路。
     *
     * <h2>契约</h2>
     * <p>与 {@code write} 完全一致：成功 → 目标内容与 {@code sink} 写入的字节逐字节相等且不留
     * {@code .tmp}；失败（含 {@code sink} 自己抛异常）→ 返回 {@code false}，<b>目标文件一个字节
     * 都不动</b>，并尽力清理 {@code .tmp}。
     *
     * <p><b>失败判定只看返回码，不看「写了多少字节」</b>：{@code sink} 中途抛异常时，临时文件里
     * 已经有半截内容，但那一份永远不会被 rename 过去 —— 这正是本方法要买的东西。
     *
     * <h2>线程</h2>
     * <p>同一 {@code target} 的并发写不在契约内（与 {@code write} 同）。
     */
    public static boolean writeStream(Path target, ByteSink sink, Logger logger) {
        return writeStream(target, sink, logger, tmp -> deleteQuietly(tmp, logger));
    }

    /** @see #writeStream(Path, ByteSink, Logger) */
    public static boolean writeStream(Path target, ByteSink sink, Logger logger,
                                      Consumer<Path> tmpCleanup) {
        Path absolute = target.toAbsolutePath();
        if (!ensureParentDirectory(absolute, logger)) {
            return false;
        }

        Path tmp = Path.of(absolute + TMP_SUFFIX);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
            sink.writeTo(out);
        } catch (IOException | RuntimeException e) {
            // sink 自己的 IO 失败（网络中断）也走这里：语义与写盘失败相同 —— 没写出去。
            logger.log(Level.SEVERE, "Failed to write temporary file " + tmp, e);
            tmpCleanup.accept(tmp);
            return false;
        }

        return moveIntoPlace(tmp, absolute, logger, tmpCleanup);
    }

    /**
     * 流式原子写的落盘动作：把内容写进传入的 {@link OutputStream}。
     *
     * <p><b>为什么不让实现方直接拿到 {@code Path}</b>：那样实现方就可能自己去 open 目标文件、
     * 绕过临时文件，"原子写" 变成一句口头约定。这里只给一个流，写入方无法知道最终路径。
     *
     * <p>不要求实现方 {@code close}/{@code flush} 该流 —— 由 {@link AtomicFileWriter} 负责，
     * 且 {@code flush} 失败同样会走失败路径（否则缓冲区里的最后一块可能没落盘就报成功）。
     */
    @FunctionalInterface
    public interface ByteSink {
        void writeTo(OutputStream out) throws IOException;
    }

    /** 建父目录（对应 {@code FileConfiguration.save} 的 createParentDirs）；失败即判整体失败。 */
    private static boolean ensureParentDirectory(Path absolute, Logger logger) {
        Path parent = absolute.getParent();
        if (parent == null) {
            return true;
        }
        try {
            Files.createDirectories(parent);
            return true;
        } catch (IOException | RuntimeException e) {
            // 不让后续 write 抛一个语义更模糊的 NoSuchFileException。
            logger.log(Level.SEVERE,
                    "Failed to create parent directory " + parent + " for " + absolute, e);
            return false;
        }
    }

    /** 临时文件 → 目标路径。失败时清理 {@code .tmp} 并返回 false（目标保持原样）。 */
    private static boolean moveIntoPlace(Path tmp, Path absolute, Logger logger,
                                         Consumer<Path> tmpCleanup) {
        try {
            Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException e) {
            // 某些文件系统（网络盘 / 容器挂载 / zipfs）不支持原子 rename：退化为普通替换。
            // 此时临时文件仍在，下面这一步才是真正的"移动"。
            try {
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException fallbackFailure) {
                logger.log(Level.SEVERE, "Failed to replace " + absolute, fallbackFailure);
                tmpCleanup.accept(tmp);
                return false;
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to replace " + absolute, e);
            tmpCleanup.accept(tmp);
            return false;
        }
    }

    /**
     * 尽力清理临时文件。
     *
     * <p><b>为什么失败要记日志而不是静默吞掉</b>：残留的普通文件确实无害 —— 下一次
     * {@code Files.writeString} 会直接覆写它。但若 {@code .tmp} 路径被<b>非空目录</b>占住，
     * {@code deleteIfExists} 会抛 {@code DirectoryNotEmptyException}，而
     * {@code Files.writeString} 对已存在的目录同样会失败 —— 于是该目标文件的<b>每一次</b>
     * 后续写入都会失败，且原因只有一条早已被吞掉的异常。实测复现过这个「永久堵死」形态。
     *
     * <p>因此这里区分「删除失败」与「删除成功」：失败时记一条 WARNING 指明残留路径，
     * 让运维至少知道该去手工删哪个东西。不抛异常 —— 清理本身是尽力而为，调用方
     * 已经在处理一次落盘失败了，不该因为清理再抛一次。
     */
    public static void deleteQuietly(Path path, Logger logger) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException e) {
            if (e instanceof java.nio.file.DirectoryNotEmptyException) {
                // 这条单独拎出来：它不是"临时文件残留"，而是会让该路径永久写不进去。
                logger.warning("Cannot remove temp path " + path + " because it is a NON-EMPTY"
                        + " DIRECTORY. Every subsequent save to the corresponding target file will"
                        + " fail until it is removed manually.");
            } else {
                logger.log(Level.WARNING, "Failed to clean up temp file " + path, e);
            }
        }
    }
}
