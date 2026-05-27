package logic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Parallel processor for SHACL shape simplification.
 * 
 * Provides batch processing with configurable parallelism,
 * similar to Python's ThreadPoolExecutor pattern.
 */
public class ParallelSimplifier {
    private static final Logger log = LoggerFactory.getLogger(ParallelSimplifier.class);
    
    // Default number of workers (based on available CPUs)
    private static final int DEFAULT_WORKERS = Math.max(1, 
        Runtime.getRuntime().availableProcessors() - 1);
    
    private final int maxWorkers;
    private final boolean parallelEnabled;
    
    // Statistics
    private long totalProcessingTime = 0;
    private int totalItemsProcessed = 0;
    
    public ParallelSimplifier() {
        this(DEFAULT_WORKERS, true);
    }
    
    public ParallelSimplifier(int maxWorkers, boolean parallelEnabled) {
        this.maxWorkers = maxWorkers > 0 ? maxWorkers : DEFAULT_WORKERS;
        this.parallelEnabled = parallelEnabled;
    }
    
    /**
     * Process items in parallel using the provided processor function.
     * Falls back to sequential processing if parallel is disabled or for small lists.
     * 
     * @param items The items to process
     * @param processor The processing function
     * @param <T> Input type
     * @param <R> Output type
     * @return List of processed results
     */
    public <T, R> List<R> process(List<T> items, Function<T, R> processor) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        
        long startTime = System.currentTimeMillis();
        List<R> results;
        
        // Use parallel processing for larger workloads
        if (parallelEnabled && items.size() > maxWorkers) {
            results = processParallel(items, processor);
        } else {
            results = processSequential(items, processor);
        }
        
        totalProcessingTime += System.currentTimeMillis() - startTime;
        totalItemsProcessed += items.size();
        
        return results;
    }
    
    /**
     * Process items sequentially.
     */
    private <T, R> List<R> processSequential(List<T> items, Function<T, R> processor) {
        List<R> results = new ArrayList<>(items.size());
        for (T item : items) {
            try {
                R result = processor.apply(item);
                if (result != null) {
                    results.add(result);
                }
            } catch (Exception e) {
                log.warn("Error processing item: {}", e.getMessage());
            }
        }
        return results;
    }
    
    /**
     * Process items in parallel using ExecutorService.
     */
    private <T, R> List<R> processParallel(List<T> items, Function<T, R> processor) {
        ExecutorService executor = Executors.newFixedThreadPool(maxWorkers);
        List<R> results = new CopyOnWriteArrayList<>();
        
        try {
            // Submit all tasks
            List<Future<R>> futures = new ArrayList<>();
            for (T item : items) {
                futures.add(executor.submit(() -> processor.apply(item)));
            }
            
            // Collect results in order
            for (Future<R> future : futures) {
                try {
                    R result = future.get();
                    if (result != null) {
                        results.add(result);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Processing interrupted");
                    break;
                } catch (ExecutionException e) {
                    log.warn("Error during parallel processing: {}", e.getCause().getMessage());
                }
            }
            
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        return new ArrayList<>(results);
    }
    
    /**
     * Process items in batches (for very large workloads).
     * Similar to Python's batch processing approach.
     * 
     * @param items Items to process
     * @param batchSize Size of each batch
     * @param batchProcessor Function to process a batch
     * @param <T> Input type
     * @param <R> Output type
     * @return List of all processed results
     */
    public <T, R> List<R> processBatched(List<T> items, int batchSize, 
                                         Function<List<T>, List<R>> batchProcessor) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Split into batches
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        
        log.debug("Processing {} items in {} batches of size {}", 
                items.size(), batches.size(), batchSize);
        
        // Process batches (can be parallel or sequential)
        List<List<R>> batchResults = process(batches, batchProcessor);
        
        // Flatten results
        List<R> allResults = new ArrayList<>();
        for (List<R> batchResult : batchResults) {
            allResults.addAll(batchResult);
        }
        
        return allResults;
    }
    
    /**
     * Get the number of workers being used.
     */
    public int getMaxWorkers() {
        return maxWorkers;
    }
    
    /**
     * Check if parallel processing is enabled.
     */
    public boolean isParallelEnabled() {
        return parallelEnabled;
    }
    
    /**
     * Get processing statistics.
     */
    public ProcessingStats getStats() {
        return new ProcessingStats(totalProcessingTime, totalItemsProcessed);
    }
    
    /**
     * Reset statistics.
     */
    public void resetStats() {
        totalProcessingTime = 0;
        totalItemsProcessed = 0;
    }
    
    /**
     * Processing statistics record.
     */
    public record ProcessingStats(long totalTimeMs, int itemsProcessed) {
        public double getAverageTimePerItem() {
            return itemsProcessed > 0 ? (double) totalTimeMs / itemsProcessed : 0;
        }
        
        @Override
        public String toString() {
            return String.format("ProcessingStats[time=%dms, items=%d, avg=%.2fms/item]",
                    totalTimeMs, itemsProcessed, getAverageTimePerItem());
        }
    }
}
