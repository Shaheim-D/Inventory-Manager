package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.domain.AssetCategory;
import com.midhudsonfiber.inventory.domain.LifecycleState;
import com.midhudsonfiber.inventory.domain.LifecycleTransition;
import com.midhudsonfiber.inventory.repo.LifecycleStateRepository;
import com.midhudsonfiber.inventory.repo.LifecycleTransitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gives a newly created category a working lifecycle graph.
 *
 * <p>Without this, adding a category through the admin screen produced one that
 * no asset could be created in — the first attempt failed with "has no lifecycle
 * transitions configured yet", which is a dead end presented as a validation
 * error. A new category now arrives usable and the graph stays fully editable.
 */
@Service
public class LifecycleGraphSeeder {

    /** Serialized equipment. No QA step: this organization does not perform one. */
    public static final List<Map.Entry<String, String>> SERIALIZED_GRAPH = List.of(
            Map.entry("Ordered", "Received"),
            Map.entry("Received", "Available"),
            Map.entry("Available", "Reserved"),
            Map.entry("Reserved", "Installed"),
            Map.entry("Installed", "Active"),
            Map.entry("Active", "Repair"),
            Map.entry("Repair", "Active"),
            Map.entry("Repair", "Retired"),
            Map.entry("Active", "Retired"),
            Map.entry("Available", "Retired"),
            Map.entry("Retired", "Disposed"));

    /** Bulk stock: in, out, gone. Reserved and Repair mean nothing for a spool of cable. */
    public static final List<Map.Entry<String, String>> BULK_GRAPH = List.of(
            Map.entry("Ordered", "Received"),
            Map.entry("Received", "Available"),
            Map.entry("Available", "Installed"),
            Map.entry("Available", "Disposed"),
            Map.entry("Installed", "Disposed"));

    private final LifecycleStateRepository states;
    private final LifecycleTransitionRepository transitions;

    public LifecycleGraphSeeder(LifecycleStateRepository states, LifecycleTransitionRepository transitions) {
        this.states = states;
        this.transitions = transitions;
    }

    /** Picks the shape from whether the category tracks units or a quantity. */
    @Transactional
    public int seedDefaultGraph(AssetCategory category) {
        return seed(category, category.isSerialized() ? SERIALIZED_GRAPH : BULK_GRAPH);
    }

    @Transactional
    public int seed(AssetCategory category, List<Map.Entry<String, String>> edges) {
        int created = 0;
        for (Map.Entry<String, String> edge : edges) {
            Optional<LifecycleState> from = states.findByName(edge.getKey());
            Optional<LifecycleState> to = states.findByName(edge.getValue());
            if (from.isEmpty() || to.isEmpty()) continue;

            boolean exists = transitions.existsByCategoryIdAndFromStateIdAndToStateId(
                    category.getId(), from.get().getId(), to.get().getId());
            if (exists) continue;

            LifecycleTransition transition = new LifecycleTransition();
            transition.setCategory(category);
            transition.setFromState(from.get());
            transition.setToState(to.get());
            transitions.save(transition);
            created++;
        }
        return created;
    }
}
