package com.example.kairo.agent.core;

import com.example.kairo.api.CallSiteSelector;
import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.EnhancementTarget;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.api.snapshot.CallSiteSnapshot;
import com.example.kairo.api.snapshot.ChainSnapshot;
import com.example.kairo.api.snapshot.CollectionTruncation;
import com.example.kairo.api.snapshot.RuleSnapshot;
import com.example.kairo.api.snapshot.SnapshotBounds;
import com.example.kairo.api.snapshot.SnapshotTruncation;
import com.example.kairo.core.ChainEntryProjector;
import com.example.kairo.core.CompiledRule;
import com.example.kairo.core.RuleChainSnapshot;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * V1.7 M1-C &sect;8.3: builds a bounded, read-only {@link AgentRuntimeSnapshot} from the Agent's
 * live in-memory state. The caller ({@code AgentRuntime.snapshotRuntimeState}) reads the state
 * under the snapshot read lock and passes lazy, read-only iteration sources here, so the builder
 * reads one consistent logical view; it never calls enhance, unload, compile, decompile, transform
 * or discover.
 *
 * <p><b>Bounded memory.</b> The builder streams each source once and retains only a bounded top-K
 * of each collection (the configured entry-count bounds) in a priority queue, plus the totals it
 * counts while scanning. It never copies or builds an unbounded list/map/payload: even an
 * over-limit source produces at most {@code MAX_RULES + MAX_CHAINS + MAX_DEGRADED} retained DTOs
 * plus constant working overhead before the byte reduction. The retained top-K is the
 * stable-sorted prefix (smallest sort keys), so reduction is deterministic and reproducible.
 *
 * <p><b>rules[] from chains.</b> The authoritative rule store is the {@link
 * com.example.kairo.core.RuleRegistry} chain state (APPLY_CHAIN writes only there). The builder
 * derives {@code rules[]} from every chain snapshot's compiled rules, so a chain-only rule appears
 * in both {@code rules[]} and {@code chains[]} without a second mutable source of truth.
 *
 * <p>After the bounded collection, the serialized-byte cap is enforced by reducing the collections
 * in a deterministic priority order (chains, then rules, then degraded classes) until the
 * deterministic UTF-8 JSON fits.
 */
final class RuntimeStateSnapshotBuilder {

    /**
     * Deterministic mapper matching the Platform's {@code PlatformJson}: sorted properties and
     * ordered map entries so the byte count is reproducible on both sides.
     */
    private static final JsonMapper SORTED_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private RuntimeStateSnapshotBuilder() {
    }

    /**
     * Build a bounded snapshot from lazy iteration sources. Both sources are consumed once under
     * the caller's snapshot read lock.
     *
     * @param chainSource   visits every live (non-empty) chain snapshot (e.g. {@code ruleRegistry::forEachChain})
     * @param degradedSource visits every degraded class (name, reason) (e.g. {@code map::forEach})
     */
    static AgentRuntimeSnapshot build(Consumer<Consumer<RuleChainSnapshot>> chainSource,
                                       Consumer<BiConsumer<String, String>> degradedSource,
                                       String agentVersion, boolean disabled, boolean emergency,
                                       String agentId, String processStartId) {
        TopK<ChainSnapshot> chains = new TopK<>(SnapshotBounds.MAX_CHAINS,
                Comparator.comparing((ChainSnapshot c) -> c.chainId()));
        TopK<RuleSnapshot> rules = new TopK<>(SnapshotBounds.MAX_RULES,
                Comparator.comparing((RuleSnapshot r) -> r.ruleId()));
        int[] totals = new int[3];
        chainSource.accept(chain -> {
            ChainSnapshot chainDto = toChainSnapshot(chain);
            chains.offer(chainDto);
            totals[1]++;
            List<CompiledRule> chainRules = chain.rules();
            for (CompiledRule compiled : chainRules) {
                rules.offer(toRuleSnapshot(compiled));
                totals[0]++;
            }
        });
        TopK<String> degraded = new TopK<>(SnapshotBounds.MAX_DEGRADED_CLASSES,
                Comparator.<String>naturalOrder());
        degradedSource.accept((name, reason) -> {
            degraded.offer(name);
            totals[2]++;
        });

        List<ChainSnapshot> allChains = chains.drain();
        List<RuleSnapshot> allRules = rules.drain();
        List<String> allDegraded = degraded.drain();
        int totalRules = totals[0];
        int totalChains = totals[1];
        int totalDegraded = totals[2];

        // The count bound is already applied by the top-K retention; bound = min(total, MAX).
        int rulesBound = Math.min(totalRules, SnapshotBounds.MAX_RULES);
        int chainsBound = Math.min(totalChains, SnapshotBounds.MAX_CHAINS);
        int degradedBound = Math.min(totalDegraded, SnapshotBounds.MAX_DEGRADED_CLASSES);

        // Enforce the serialized-byte cap: reduce collections in priority order (chains, then
        // rules, then degraded classes) until the deterministic JSON fits.
        int rulesCount = allRules.size();
        int chainsCount = allChains.size();
        int degradedCount = allDegraded.size();
        long measured = measureForCap(allRules, allChains, allDegraded, totalRules, totalChains,
                totalDegraded, rulesBound, chainsBound, degradedBound, rulesCount, chainsCount, degradedCount,
                agentVersion, disabled, emergency, agentId, processStartId);
        chainsCount = reduceToByteCap(1, rulesCount, chainsCount, degradedCount, measured,
                allRules, allChains, allDegraded, totalRules, totalChains, totalDegraded,
                rulesBound, chainsBound, degradedBound, agentVersion, disabled, emergency, agentId, processStartId);
        measured = measureForCap(allRules, allChains, allDegraded, totalRules, totalChains,
                totalDegraded, rulesBound, chainsBound, degradedBound, rulesCount, chainsCount, degradedCount,
                agentVersion, disabled, emergency, agentId, processStartId);
        rulesCount = reduceToByteCap(0, rulesCount, chainsCount, degradedCount, measured,
                allRules, allChains, allDegraded, totalRules, totalChains, totalDegraded,
                rulesBound, chainsBound, degradedBound, agentVersion, disabled, emergency, agentId, processStartId);
        measured = measureForCap(allRules, allChains, allDegraded, totalRules, totalChains,
                totalDegraded, rulesBound, chainsBound, degradedBound, rulesCount, chainsCount, degradedCount,
                agentVersion, disabled, emergency, agentId, processStartId);
        degradedCount = reduceToByteCap(2, rulesCount, chainsCount, degradedCount, measured,
                allRules, allChains, allDegraded, totalRules, totalChains, totalDegraded,
                rulesBound, chainsBound, degradedBound, agentVersion, disabled, emergency, agentId, processStartId);

        long finalBytes = resolveSerializedBytes(allRules, allChains, allDegraded, totalRules,
                totalChains, totalDegraded, rulesBound, chainsBound, degradedBound,
                rulesCount, chainsCount, degradedCount, agentVersion, disabled, emergency, agentId, processStartId);
        return assemble(allRules, allChains, allDegraded, totalRules, totalChains, totalDegraded,
                rulesBound, chainsBound, degradedBound, rulesCount, chainsCount, degradedCount,
                agentVersion, disabled, emergency, agentId, processStartId, finalBytes);
    }

    /**
     * A bounded top-K collector: retains the {@code k} smallest elements (by {@code comparator}) in
     * a max-heap, plus a running total of every element offered. Memory is bounded to {@code k}
     * retained elements regardless of how many the source produces.
     */
    private static final class TopK<T> {
        private final PriorityQueue<T> heap;
        private final int k;
        private final Comparator<T> comparator;

        TopK(int k, Comparator<T> comparator) {
            this.k = k;
            this.comparator = comparator;
            // reversed so the head is the largest retained; evict it when a smaller element arrives.
            this.heap = new PriorityQueue<>(comparator.reversed());
        }

        void offer(T element) {
            if (k <= 0) {
                return;
            }
            if (heap.size() < k) {
                heap.add(element);
            } else {
                T largest = heap.peek();
                if (comparator.compare(element, largest) < 0) {
                    heap.poll();
                    heap.add(element);
                }
            }
        }

        /** Drain the retained top-K into a stable-sorted (ascending) list. */
        List<T> drain() {
            List<T> sorted = new ArrayList<>(heap);
            sorted.sort(comparator);
            return sorted;
        }
    }

    private static int reduceToByteCap(int idx, int rulesCount, int chainsCount, int degradedCount,
                                       long current, List<RuleSnapshot> allRules, List<ChainSnapshot> allChains,
                                       List<String> allDegraded, int totalRules, int totalChains, int totalDegraded,
                                       int rulesBound, int chainsBound, int degradedBound,
                                       String agentVersion, boolean disabled, boolean emergency,
                                       String agentId, String processStartId) {
        if (current <= SnapshotBounds.MAX_SERIALIZED_BYTES) {
            return countAt(idx, rulesCount, chainsCount, degradedCount);
        }
        int lo = 0;
        int hi = countAt(idx, rulesCount, chainsCount, degradedCount);
        int best = 0;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            int r = idx == 0 ? mid : rulesCount;
            int c = idx == 1 ? mid : chainsCount;
            int d = idx == 2 ? mid : degradedCount;
            long measured = measureForCap(allRules, allChains, allDegraded, totalRules, totalChains,
                    totalDegraded, rulesBound, chainsBound, degradedBound, r, c, d,
                    agentVersion, disabled, emergency, agentId, processStartId);
            if (measured <= SnapshotBounds.MAX_SERIALIZED_BYTES) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    private static int countAt(int idx, int rulesCount, int chainsCount, int degradedCount) {
        return switch (idx) {
            case 0 -> rulesCount;
            case 1 -> chainsCount;
            case 2 -> degradedCount;
            default -> throw new IllegalArgumentException("idx");
        };
    }

    /**
     * Measure the byte count for the supplied counts. The {@code serializedBytes} field is set to
     * the maximum value width (a 7-digit placeholder) so the measurement is an upper bound: the
     * final value is never wider, so a snapshot that fits here fits finally.
     */
    private static long measureForCap(List<RuleSnapshot> allRules, List<ChainSnapshot> allChains,
                                      List<String> allDegraded, int totalRules, int totalChains, int totalDegraded,
                                      int rulesBound, int chainsBound, int degradedBound,
                                      int rulesCount, int chainsCount, int degradedCount,
                                      String agentVersion, boolean disabled, boolean emergency,
                                      String agentId, String processStartId) {
        return serializedBytes(assemble(allRules, allChains, allDegraded, totalRules, totalChains,
                totalDegraded, rulesBound, chainsBound, degradedBound, rulesCount, chainsCount, degradedCount,
                agentVersion, disabled, emergency, agentId, processStartId, SnapshotBounds.MAX_SERIALIZED_BYTES));
    }

    /**
     * Resolve the fixed-point {@code serializedBytes}: the value that equals the byte count of the
     * snapshot carrying that value. Converges in one or two steps because only the value's digit
     * count changes the measurement.
     */
    private static long resolveSerializedBytes(List<RuleSnapshot> allRules, List<ChainSnapshot> allChains,
                                                List<String> allDegraded, int totalRules, int totalChains, int totalDegraded,
                                                int rulesBound, int chainsBound, int degradedBound,
                                                int rulesCount, int chainsCount, int degradedCount,
                                                String agentVersion, boolean disabled, boolean emergency,
                                                String agentId, String processStartId) {
        long reported = 0L;
        for (int i = 0; i < 4; i++) {
            long measured = serializedBytes(assemble(allRules, allChains, allDegraded, totalRules, totalChains,
                    totalDegraded, rulesBound, chainsBound, degradedBound, rulesCount, chainsCount, degradedCount,
                    agentVersion, disabled, emergency, agentId, processStartId, reported));
            if (measured == reported) {
                return reported;
            }
            reported = measured;
        }
        return reported;
    }

    private static AgentRuntimeSnapshot assemble(List<RuleSnapshot> allRules, List<ChainSnapshot> allChains,
                                                  List<String> allDegraded, int totalRules, int totalChains, int totalDegraded,
                                                  int rulesBound, int chainsBound, int degradedBound,
                                                  int rulesCount, int chainsCount, int degradedCount,
                                                  String agentVersion, boolean disabled, boolean emergency, String agentId,
                                                  String processStartId, long serializedBytes) {
        List<RuleSnapshot> rules = List.copyOf(allRules.subList(0, Math.min(rulesCount, allRules.size())));
        List<ChainSnapshot> chains = List.copyOf(allChains.subList(0, Math.min(chainsCount, allChains.size())));
        List<String> degraded = List.copyOf(allDegraded.subList(0, Math.min(degradedCount, allDegraded.size())));
        SnapshotTruncation truncation = new SnapshotTruncation(
                truncation(totalRules, rulesCount, rulesBound),
                truncation(totalChains, chainsCount, chainsBound),
                truncation(totalDegraded, degradedCount, degradedBound),
                SnapshotBounds.MAX_SERIALIZED_BYTES,
                serializedBytes);
        return new AgentRuntimeSnapshot(
                SnapshotBounds.PROTOCOL_VERSION,
                agentId,
                processStartId,
                System.currentTimeMillis(),
                agentVersion,
                disabled,
                emergency,
                chains,
                rules,
                degraded,
                truncation);
    }

    /**
     * Per-collection truncation metadata. {@code null} reason when not truncated,
     * {@link SnapshotBounds#REASON_SERIALIZED_BYTE_LIMIT} when the byte cap reduced a count below
     * its entry-count bound, otherwise {@link SnapshotBounds#REASON_ENTRY_COUNT_LIMIT}.
     */
    private static CollectionTruncation truncation(int total, int included, int bound) {
        if (included >= total) {
            return new CollectionTruncation(total, included, null);
        }
        boolean byteLimited = included < bound;
        return new CollectionTruncation(total, included,
                byteLimited ? SnapshotBounds.REASON_SERIALIZED_BYTE_LIMIT
                        : SnapshotBounds.REASON_ENTRY_COUNT_LIMIT);
    }

    private static ChainSnapshot toChainSnapshot(RuleChainSnapshot snapshot) {
        List<CompiledRule> compiled = snapshot.rules();
        // ruleIds: stable-sorted ascending (deterministic + Platform-verifiable); the canonical
        // execution order is preserved by the canonicalHash, not by this list's order.
        List<String> ruleIds = new ArrayList<>(compiled.size());
        for (CompiledRule rule : compiled) {
            ruleIds.add(rule.rule().id());
        }
        ruleIds.sort(Comparator.naturalOrder());
        ChainDesiredState desired = ChainEntryProjector.desiredStateFor(compiled);
        EnhancementTarget target = snapshot.target();
        CallSiteSnapshot callSite = null;
        if (target != null) {
            CallSiteSelector selector = target.callSiteSelector();
            if (selector != null) {
                callSite = new CallSiteSnapshot(selector.owner(), selector.name(), selector.descriptor(),
                        selector.opcode().name(), selector.occurrenceIndex(), selector.fingerprint());
            }
        }
        String chainId = snapshot.chainId();
        if (chainId == null || chainId.isBlank()) {
            chainId = target == null ? "" : RuleChainSnapshot.chainIdOf(target);
        }
        return new ChainSnapshot(
                chainId,
                target == null ? "" : target.method().className(),
                target == null ? null : target.method().classLoaderId(),
                target == null ? "" : target.method().methodName(),
                target == null ? "" : target.method().methodDescriptor(),
                target == null ? "" : target.location().name(),
                callSite,
                snapshot.revision().value(),
                snapshot.hash(),
                snapshot.transformationRevision(),
                snapshot.transformationHash(),
                desired.name(),
                List.copyOf(ruleIds),
                snapshot.degradedReason());
    }

    private static RuleSnapshot toRuleSnapshot(CompiledRule compiled) {
        com.example.kairo.api.MockRule rule = compiled.rule();
        return new RuleSnapshot(rule.id(), rule.version(), rule.enabled(), rule.expireAt());
    }

    private static long serializedBytes(AgentRuntimeSnapshot snapshot) {
        try {
            return SORTED_MAPPER.writeValueAsBytes(snapshot).length;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize runtime state snapshot", e);
        }
    }
}
