package org.dsol.planner.api;

import java.util.ArrayList;
import java.util.List;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;


@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "action", propOrder = { "name", "params", "post","pre","reliability","seam", "qos" })
public class GenericAbstractAction extends AbstractAction {

	private boolean enabled;
	private String name;
	private List<String> params;
	private List<String> post;
	private List<String> pre;
	private double reliability;
	
	private boolean seam;
	
	public GenericAbstractAction() {
		params = new ArrayList<String>();
		post = new ArrayList<String>();
		pre = new ArrayList<String>();
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		GenericAbstractAction other = (GenericAbstractAction) obj;
		if (enabled != other.enabled)
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (params == null) {
			if (other.params != null)
				return false;
		} else if (!params.equals(other.params))
			return false;
		if (post == null) {
			if (other.post != null)
				return false;
		} else if (!post.equals(other.post))
			return false;
		if (pre == null) {
			if (other.pre != null)
				return false;
		} else if (!pre.equals(other.pre))
			return false;
		if (Double.doubleToLongBits(reliability) != Double
				.doubleToLongBits(other.reliability))
			return false;
		if (seam != other.seam)
			return false;
		return true;
	}

	public String getName() {
		return name;
	}

	@Override
	public List<String> getParamList() {
		return params;
	}

	public List<String> getParams() {
		return params;
	}

	public List<String> getPost() {
		return post;
	}

	@Override
	public List<Fact> getPostConditions() {
		List<Fact> postConditions = new ArrayList<Fact>();
		for(String postCondition:post){
			postConditions.add(new Fact(postCondition));
		}
		
		return postConditions;
	}
	
	@Override
	public List<Fact> getPreConditions() {
		List<Fact> preConditions = new ArrayList<Fact>();
		for(String preCondition:pre){
			preConditions.add(new Fact(preCondition));
		}
		
		return preConditions;
	}

	public List<String> getPre() {
		return pre;
	}


	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + (enabled ? 1231 : 1237);
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((params == null) ? 0 : params.hashCode());
		result = prime * result + ((post == null) ? 0 : post.hashCode());
		result = prime * result + ((pre == null) ? 0 : pre.hashCode());
		long temp;
		temp = Double.doubleToLongBits(reliability);
		result = prime * result + (int) (temp ^ (temp >>> 32));
		result = prime * result + (seam ? 1231 : 1237);
		return result;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public boolean isSeam() {
		return seam;
	}

	@Override
	public boolean isTriggeredBy(AbstractAction action) {
		GenericAbstractAction thatAction = (GenericAbstractAction)action;
		
		for(String preCond:this.pre){
			if(thatAction.post.contains(preCond)){
				return true;
			}
		}
		
		return false;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
	
	public void setName(String name) {
		this.name = name;
	}

	
	public void setParams(List<String> params) {
		this.params = params;
	}

	public void setPost(List<String> post) {
		this.post = post;
	}

	public void setPre(List<String> pre) {
		this.pre = pre;
	}

	public void setSeam(boolean seam) {
		this.seam = seam;
	}

	//Generated by eclipse
	
}