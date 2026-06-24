package forge.headless.protocol;

import java.util.List;
import java.util.Map;

/**
 * The answer to a DecisionRequest, correlated by id. Only the field
 * relevant to the request's type is expected to be populated.
 */
public class DecisionResponse {
    public String id;
    public Boolean booleanValue;
    public List<String> chosenIds;
    /** Only meaningful for DECLARE_BLOCKERS: group id -> chosen blocker
     * option ids for that attacker. */
    public Map<String, List<String>> groupChoices;

    public DecisionResponse() { }
}
