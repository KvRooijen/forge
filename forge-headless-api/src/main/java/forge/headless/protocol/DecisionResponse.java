package forge.headless.protocol;

import java.util.List;

/**
 * The answer to a DecisionRequest, correlated by id. Only the field
 * relevant to the request's type is expected to be populated.
 */
public class DecisionResponse {
    public String id;
    public Boolean booleanValue;
    public List<String> chosenIds;

    public DecisionResponse() { }
}
