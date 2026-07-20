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

        public static Step attribute(String name) {
            return new Step(name, null, null);
        }

        public static Step stringKey(String key) {
            return new Step(null, key, null);
        }

        public static Step intKey(long index) {
            return new Step(null, null, index);
        }

        /** An unset selector (proto {@code SELECTOR_NOT_SET}); renders as {@code <?>}. */
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
