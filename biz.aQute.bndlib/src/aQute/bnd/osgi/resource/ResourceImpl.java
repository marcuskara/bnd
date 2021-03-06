package aQute.bnd.osgi.resource;

import java.util.*;

import org.osgi.framework.namespace.*;
import org.osgi.resource.*;

class ResourceImpl implements Resource {

	private List<Capability>				allCapabilities;
	private Map<String,List<Capability>>	capabilityMap;

	private List<Requirement>				allRequirements;
	private Map<String,List<Requirement>>	requirementMap;

	void setCapabilities(List<Capability> capabilities) {
		allCapabilities = capabilities;

		capabilityMap = new HashMap<String,List<Capability>>();
		for (Capability capability : capabilities) {
			List<Capability> list = capabilityMap.get(capability.getNamespace());
			if (list == null) {
				list = new LinkedList<Capability>();
				capabilityMap.put(capability.getNamespace(), list);
			}
			list.add(capability);
		}
	}

	public List<Capability> getCapabilities(String namespace) {
		List<Capability> caps = allCapabilities;
		if ( namespace != null)
			caps = capabilityMap.get(namespace);
		if ( caps == null || caps.isEmpty())
			return Collections.emptyList();
		
		return Collections.unmodifiableList(caps);
	}

	void setRequirements(List<Requirement> requirements) {
		allRequirements = requirements;

		requirementMap = new HashMap<String,List<Requirement>>();
		for (Requirement requirement : requirements) {
			List<Requirement> list = requirementMap.get(requirement.getNamespace());
			if (list == null) {
				list = new LinkedList<Requirement>();
				requirementMap.put(requirement.getNamespace(), list);
			}
			list.add(requirement);
		}
	}

	public List<Requirement> getRequirements(String namespace) {
		List<Requirement> reqs = allRequirements;
		if ( namespace != null)
			reqs = requirementMap.get(namespace);
		if ( reqs == null || reqs.isEmpty())
			return Collections.emptyList();
		
		return Collections.unmodifiableList(reqs);
	}

	@Override
	public String toString() {
		final StringBuilder builder = new StringBuilder();
		List<Capability> identities = getCapabilities(IdentityNamespace.IDENTITY_NAMESPACE);
		if (identities != null && identities.size() == 1) {
			Capability idCap = identities.get(0);
			Object id = idCap.getAttributes().get(IdentityNamespace.IDENTITY_NAMESPACE);
			Object version = idCap.getAttributes().get(IdentityNamespace.CAPABILITY_VERSION_ATTRIBUTE);
			
			builder.append(id).append(" version=").append(version);
		} else {
			// Generic toString
			builder.append("ResourceImpl [caps=");
			builder.append(allCapabilities);
			builder.append(", reqs=");
			builder.append(allRequirements);
			builder.append("]");
		}
		return builder.toString();
	}

}
