package cloud.kitelang.tfplugin;

import java.util.List;

/**
 * Protocol-neutral attribute path from a {@code PlanResourceChange} response's
 * {@code requires_replace} list. Both tfplugin5 and tfplugin6 define an identical
 * {@code AttributePath} message; this record carries the steps so the rendering
 * logic lives in one place instead of per-protocol adapters.
 *
 * @param steps the ordered path steps from the root of the resource object
 */
public record TfAttributePath(List<Step> steps) {

    /** Defensively copies {@link #steps} to an immutable list. */
    public TfAttributePath {
        steps = List.copyOf(steps);
    }

    /**
     * One path step. Exactly one component is non-null, mirroring the proto
     * {@code oneof selector}; an all-null step represents an unset selector.
     *
     * @param attributeName    attribute lookup in the current object, or null
     * @param elementKeyString string key lookup in an indexable collection, or null
     * @param elementKeyInt    integer index lookup in an indexable collection, or null
     */
    public record Step(String attributeName, String elementKeyString, Long elementKeyInt) {

        /**
         * An attribute-name selector, e.g. the {@code length} step in {@code tags.length}.
         *
         * @param name the attribute name in the current object
         * @return a step selecting that attribute
         */
        public static Step attribute(String name) {
            return new Step(name, null, null);
        }

        /**
         * A string-key selector into a map or set, e.g. {@code "env"} in {@code tags["env"]}.
         *
         * @param key the string key
         * @return a step selecting that key
         */
        public static Step stringKey(String key) {
            return new Step(null, key, null);
        }

        /**
         * An integer-index selector into a list, e.g. {@code 0} in {@code ingress[0]}.
         *
         * @param index the zero-based element index
         * @return a step selecting that index
         */
        public static Step intKey(long index) {
            return new Step(null, null, index);
        }

        /**
         * An unset selector (proto {@code SELECTOR_NOT_SET}); renders as {@code <?>}.
         *
         * @return a step with no selector set
         */
        public static Step unset() {
            return new Step(null, null, null);
        }
    }

    /**
     * Renders the path for log output, e.g. {@code length} or {@code tags["env"]}
     * or {@code ingress[0].from_port}.
     *
     * @return the human-readable path string
     */
    public String render() {
        var rendered = new StringBuilder();
        for (var step : steps) {
            if (step.attributeName() != null) {
                if (!rendered.isEmpty()) {
                    rendered.append('.');
                }
                rendered.append(step.attributeName());
            } else if (step.elementKeyString() != null) {
                rendered.append("[\"").append(step.elementKeyString()).append("\"]");
            } else if (step.elementKeyInt() != null) {
                rendered.append('[').append(step.elementKeyInt()).append(']');
            } else {
                rendered.append("<?>");
            }
        }
        return rendered.toString();
    }
}
