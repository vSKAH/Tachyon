package tech.skworks.tachyon.plugin.spigot.task;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Level;

/**
 * Project Tachyon
 * Class ChunkedTask
 *
 * <p>Splits manipulating large amounts of work into smaller batches (chunks) spread across
 * multiple server ticks to preserve TPS.</p>
 *
 * <p>Implemented securely over native {@link BukkitRunnable} timed cycles.</p>
 *
 * @author  Jimmy (vSKAH)
 * @version 1.0
 * @since 1.0.0-SNAPSHOT
 */
public abstract class ChunkedTask extends BukkitRunnable {

    private final JavaPlugin plugin;
    private final int processAmount;

    private int waitPeriodTicks;
    private int currentIndex = 0;
    private boolean processing = false;

    protected boolean logActions = true;

    /**
     * Creates a new task that will process the given amount of times on each run
     * and wait 20 ticks between each batch.
     *
     * @param processAmount The amount of elements to process per batch.
     */
    public ChunkedTask(int processAmount, JavaPlugin plugin) {
        this(processAmount, 20, plugin);
    }

    /**
     * Creates a new task with a specified plugin instance.
     *
     * @param processAmount   The amount of elements to process per batch.
     * @param waitPeriodTicks The ticks to wait between batches.
     * @param plugin          The JavaPlugin instance executing the task.
     */
    public ChunkedTask(int processAmount, int waitPeriodTicks, JavaPlugin plugin) {
        this.processAmount = processAmount;
        this.waitPeriodTicks = waitPeriodTicks;
        this.plugin = plugin;
    }


    public JavaPlugin getPlugin() {
        return this.plugin;
    }

    public void setWaitPeriodTicks(int waitPeriodTicks) {
        this.waitPeriodTicks = waitPeriodTicks;
    }

    public int getCurrentIndex() {
        return this.currentIndex;
    }

    protected void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public boolean isProcessing() {
        return this.processing;
    }

    public void setLogActions(boolean logActions) {
        this.logActions = logActions;
    }


    /**
     * Starts the chunked processing chain.
     */
    public final void start() {
        if (this.processing) return;

        this.processing = true;
        this.runTaskTimer(this.plugin, 0L, this.waitPeriodTicks);
    }

    /**
     * Forces the task to stop prematurely.
     */
    @Override
    public final synchronized void cancel() throws IllegalStateException {
        if (!this.processing) {
            this.plugin.getLogger().warning("Attempted to cancel a ChunkedTask that is not running.");
            return;
        }

        super.cancel();
        this.processing = false;
        this.onFinish(false);
    }

    /**
     * Forces the task to complete immediately in a single tick (Blocking).
     * Useful during plugin onDisable() to ensure data is saved.
     */
    public final void completeInstantly() {
        if (!this.processing) return;

        super.cancel();
        this.processing = false;

        while (canContinue(this.currentIndex)) {
            try {
                onProcess(this.currentIndex);
            } catch (Throwable t) {
                this.plugin.getLogger().log(Level.SEVERE, "Fatal error completing task instantly at index " + this.currentIndex, t);
                break;
            }
            this.currentIndex++;
        }
        this.onFinish(true);
    }

    /**
     * The internal loop executed every X ticks by Bukkit.
     * Do not call this manually.
     */
    @Override
    public void run() {
        if (!this.processing) {
            super.cancel();
            return;
        }

        final long startTime = System.currentTimeMillis();
        int processedThisTick = 0;
        boolean reachedEnd = false;

        for (int i = 0; i < this.processAmount; i++) {
            if (!this.canContinue(this.currentIndex)) {
                reachedEnd = true;
                break;
            }

            try {
                this.onProcess(this.currentIndex);
            } catch (Throwable t) {
                this.plugin.getLogger().log(Level.SEVERE, "Error in " + this.getClass().getSimpleName() + " at index " + this.currentIndex, t);
                super.cancel();
                this.processing = false;
                this.onFinish(false);
                return;
            }

            this.currentIndex++;
            processedThisTick++;
        }

        if (this.logActions && processedThisTick > 0) {
            this.plugin.getLogger().info(getProcessMessage(startTime, processedThisTick));
        }

        if (reachedEnd) {
            super.cancel();
            this.processing = false;
            this.onFinish(true);
        }
    }


    /**
     * Called when a single item is processed.
     *
     * @param index The current index being processed.
     * @throws Throwable If any error occurs during processing.
     */
    protected abstract void onProcess(int index) throws Throwable;

    /**
     * Validates if the task has more items to process.
     *
     * @param index The current target index.
     * @return true if the loop should process this index.
     */
    protected abstract boolean canContinue(int index);

    /**
     * Called when the entire operation finishes.
     *
     * @param gracefully true if finished naturally, false if cancelled prematurely.
     */
    protected void onFinish(boolean gracefully) {}

    protected String getProcessMessage(long startTime, int processed) {
        long elapsed = System.currentTimeMillis() - startTime;
        if (processed < 1000) {
            return "Processed " + processed + " " + getLabel() + ". Took " + elapsed + " ms";
        }
        return String.format("Processed %,d %s. Took %d ms", processed, getLabel(), elapsed);
    }

    protected String getLabel() {
        return "elements";
    }
}
