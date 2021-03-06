package org.dsol;

import static org.dsol.DSOLConstants.DEFAULT_BASE_INSTANCE_ID;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.jws.WebParam;

import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;

import org.apache.commons.beanutils.PropertyUtils;
import org.dsol.annotation.Concrete;
import org.dsol.annotation.DestroyInstance;
import org.dsol.annotation.StoreInstance;
import org.dsol.config.DSOLConfig;
import org.dsol.config.MethodsInfo;
import org.dsol.config.OrchestrationInterfaceInfo;
import org.dsol.config.VersionManager;
import org.dsol.engine.Interpreter;
import org.dsol.management.Actions;
import org.dsol.management.ManagementCallback;
import org.dsol.planner.api.Fact;
import org.dsol.planner.api.Planner;
import org.dsol.service.ServiceSelector;
import org.dsol.util.Util;

import com.thoughtworks.paranamer.CachingParanamer;
import com.thoughtworks.paranamer.Paranamer;

public abstract class WebServiceProxy implements MethodInterceptor {

	private ServiceSelector serviceSelector;
	private VersionManager versionManager;

	private DSOLConfig dsolConfig;
	private Instance defaultBaseInstance = null;

	private Map<String, Instance> instances;
	private Map<String, String> instancesRef;
	
	
	/*
	 * Used only to keep track of the used ids
	 * 
	 */
	private Set<String> refIds;
	
	private List<Instance> onGoingInstances;

	Lock lockUpdates = new ReentrantLock();

	/**
	 * Only for tests purposes
	 */
	protected WebServiceProxy() {
		instances = new HashMap<String, Instance>();
		instancesRef = new HashMap<String, String>();
		refIds = new HashSet<String>();
		onGoingInstances = new ArrayList<Instance>();
	}

	public WebServiceProxy(		DSOLConfig dsolConfig, 
								ServiceSelector serviceSelector,
								VersionManager versionManager,
								OrchestrationInterfaceInfo orchestrationInterfaceInfo) {

		this();
		this.dsolConfig = dsolConfig;
		this.serviceSelector = serviceSelector;
		this.versionManager = versionManager;
		
		this.defaultBaseInstance = new Instance(DEFAULT_BASE_INSTANCE_ID,
												DEFAULT_BASE_INSTANCE_ID, 
												versionManager.getCurrentVersion(),
												getPlannerRelatedToMethod(null, null),
												serviceSelector,
												dsolConfig.getConcreteActionClasses(),
												dsolConfig.getClasspathFolder(),
												orchestrationInterfaceInfo);
	}

	public Object intercept(Object obj, Method method, Object[] args,
			MethodProxy proxy) throws Throwable {
		return handleServiceCall(method, args);			
	}

	
	private Object handleServiceCall(Method method, Object[] args)
			throws Throwable {

		Instance instance = null;
		lockUpdates.lock();
		try{
			instance = createInstance(method, args);
			onGoingInstances.add(instance);
			List<Class<?>> exceptions = Arrays.asList(method.getExceptionTypes());
			instance.setThrowExceptions(exceptions);
		}
		finally{
			lockUpdates.unlock();
		}
		
		Interpreter dsolEngine = new Interpreter(instance);

		List<Fact> complementarInitialState = createInitialStateFromMethodSignature(method, args);
		instance.addToInitialState(complementarInitialState);

		boolean success = dsolEngine.executeService();

		if (!success) {
			throw new RuntimeException("Sorry, the service could not be executed!");
		}

		lockUpdates.lock();
		try{
			saveInstanceSessionIfSupposedTo(instance, method);
			destroyInstanceSessionIfSupposedTo(instance, method);
			onGoingInstances.remove(instance);
		}
		finally{
			lockUpdates.unlock();
		}
		
		return instance.getReturnValue(method);
	}

	private Instance createInstance(Method method, Object[] args){

		Instance baseInstance = defaultBaseInstance;

		Map<String, Object> argumentsMap = createArgumentsMap(method, args);
		// It means that this instance should be created based on an instance
		// that already exists
		if (method.isAnnotationPresent(org.dsol.annotation.Instance.class)) {
			String[] id = method.getAnnotation(
					org.dsol.annotation.Instance.class).id();
			String instanceKey = getInstanceKey(id, argumentsMap);
			baseInstance = instances.get(instanceKey);

			if (baseInstance == null) {
				throw new RuntimeException("Instance with key " + instanceKey
						+ " not found");
			}
		}

		Instance newInstance = new Instance(createRefId(),
											createInstanceSession(argumentsMap, method),
											getPlannerRelatedToMethod(baseInstance, method),
											serviceSelector,
											baseInstance.getClassesWithActions(),
											dsolConfig.getClasspathFolder());
		
		baseInstance.populate(newInstance);
		
		return newInstance;
	}

	protected Map<String, Object> createArgumentsMap(Method method,
													 Object[] args) {
		Map<String, Object> argumentsMap = new HashMap<String, Object>();

		Paranamer paranamer = new CachingParanamer();
		String[] parameterFormalNames = paranamer.lookupParameterNames(method,
																	   false); // will
																				// return
																				// null
																				// if
																				// not
																				// found

		Annotation[][] paramsAnnotations = method.getParameterAnnotations();
		String paramName = null;
		// boolean isConcrete = false;
		for (int i = 0; i < args.length; i++) {
			// isConcrete = false;
			Annotation[] paramAnnotations = paramsAnnotations[i];
			paramName = getParamNameFromAnnotation(paramAnnotations);
			// isConcrete = isConcrete(paramAnnotations);

			String instanceSessionKey = paramName;
			if (!parameterFormalNames.equals(Paranamer.EMPTY_NAMES)){
				instanceSessionKey = parameterFormalNames[i];
			}
			argumentsMap.put(instanceSessionKey, args[i]);
		}

		return argumentsMap;
	}

	protected String getInstanceKey(String[] id, Map<String, Object> objectsMap) {
		StringBuilder key = new StringBuilder();
		for (String idPart : id) {
			Object value = null;
    		try {
				value = PropertyUtils.getNestedProperty(objectsMap, idPart);
			} catch (Exception e) {}
    		
			key.append(idPart).append("=").append(value==null?"":value.toString());
			key.append(",");
		}
		if (key.length() > 0) {// remove last comma
			key.deleteCharAt(key.length() - 1);
		}
		return key.toString();
	}

	private InstanceSession createInstanceSession(
			Map<String, Object> argumentsMap, Method method) {
		InstanceSession instanceSession = new InstanceSession();

		Set<String> keys = argumentsMap.keySet();
		for (Iterator<String> it = keys.iterator(); it.hasNext();) {
			String key = it.next();
			instanceSession.put(key, argumentsMap.get(key));
		}
		
		instanceSession.putAllUserSpecificProperties(dsolConfig.getUserSpecificProperties());

		return instanceSession;
	}

	private void saveInstanceSessionIfSupposedTo(Instance instance, Method method) {
		if (method.isAnnotationPresent(StoreInstance.class)) {
			StoreInstance instanceInfo = method.getAnnotation(StoreInstance.class);
			instance.setId(instanceInfo.id());
			
			instancesRef.put(instance.getRefId(), instance.getId());
			instances.put(instance.getId(), instance);
		}
		else{
			refIds.remove(instance.getRefId());
		}
	}

	private String createRefId() {
		synchronized (refIds) {
			String refId = null;
			do {
				refId = UUID.randomUUID().toString();
			} while (refIds.contains(refId));
			refIds.add(refId);
			return refId;
		}
	}

	private void destroyInstanceSessionIfSupposedTo(Instance instance, Method method) {
		if (method.isAnnotationPresent(DestroyInstance.class)) {
			if (!method.isAnnotationPresent(org.dsol.annotation.Instance.class)) {
				throw new RuntimeException(
						"The annotation @DestroyInstance must be used together with the @Instance, that identify the Instance to be destroyed!");
			}
			org.dsol.annotation.Instance instanceInfo = method.getAnnotation(org.dsol.annotation.Instance.class);
			String[] ids = instanceInfo.id();
			
			Map<String,Object> objectsMap = new HashMap<String, Object>();
			for(String id:ids){
				objectsMap.put(id, instance.get(id));
			}
			
			String instanceKey = getInstanceKey(ids, objectsMap);
			Instance instanceToBeDestroyed = instances.remove(instanceKey);
			instancesRef.remove(instanceToBeDestroyed.getRefId());
		}
	}

	private Planner getPlannerRelatedToMethod(Instance instance, Method method) {

		
		Planner planner = getPlanner();
				
		try{
			InputStream actions = getAbstractActionsInputStream();
			InputStream initialStateStream = getInitialStateInputStream(instance, method);
			InputStream goalStream = getGoalInputStream(instance, method);
			planner.initialize(actions,initialStateStream, goalStream);
			
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}

		return planner;

	}
	
	private InputStream getAbstractActionsInputStream(){
		InputStream actions = Util.getInputStream(dsolConfig.getAbstractActionsFile());
		if(actions != null){//File not found
			return actions;
		}
		return new ByteArrayInputStream(new byte[0]);
	}
	
	private InputStream getInitialStateInputStream(Instance instance, Method method) throws IOException{
		if(instance != null && method != null){			
			String initialState = instance.getAdditionalInitialState(method);
			return new ByteArrayInputStream(initialState.getBytes());
		}
		return new ByteArrayInputStream(Planner.EMPTY_INITIAL_STATE.getBytes());
	}
	
	private InputStream getGoalInputStream(Instance instance, Method method) throws IOException{
		if(instance != null && method != null){			
			String goal = instance.getGoal(method);
			return new ByteArrayInputStream(goal.getBytes());
		}
		return new ByteArrayInputStream(Planner.EMPTY_GOAL.getBytes());
	}
	
	protected abstract Planner getPlanner();
	

	protected List<Fact> createInitialStateFromMethodSignature(Method method,
			Object[] args) {

		Paranamer paranamer = new CachingParanamer();
		String[] parameterNames = paranamer.lookupParameterNames(method, false); // will
																					// return
																					// null
																					// if
																					// not
																					// found

		List<Fact> propositions = new ArrayList<Fact>();
		Annotation[][] paramsAnnotations = method.getParameterAnnotations();
		Class[] paramTypes = method.getParameterTypes();
		for (int i = 0; i < paramsAnnotations.length; i++) {
			Annotation[] paramAnnotations = paramsAnnotations[i];
			String name = getParamNameFromAnnotation(paramAnnotations);
			boolean concretePresent = isConcrete(paramAnnotations);

			String predicateName = name;
			String term = null;
			if (concretePresent) {
				Object argument = args[i];
				if (argument != null && !argument.toString().trim().isEmpty()) {
					term = argument.toString().trim();
				} else {
					term = "empty";
				}
				
				//If the parameter is a @Concrete parameter and its type is a boolean
				//includes also the "simple" facts
				if(Boolean.class.isAssignableFrom(paramTypes[i])){
					Boolean paramValue = (Boolean)args[i];
					if(paramValue){
						propositions.add(new Fact(predicateName));		
					}
					else{
						propositions.add(new Fact("~"+predicateName));
					}	
				}
			} else if (parameterNames != null) {
				term = parameterNames[i];
			} else {
				term = name;
			}

			propositions.add(new Fact(predicateName + "(" + term + ")"));
		}
		return propositions;
	}

	private String getParamNameFromAnnotation(Annotation[] annotations) {
		String name = null;
		for (Annotation annotation : annotations) {
			if (annotation instanceof WebParam) {
				WebParam webParam = (WebParam) annotation;
				name = webParam.name();
			}
		}
		return name;
	}

	private boolean isConcrete(Annotation[] annotations) {
		for (int j = 0; j < annotations.length; j++) {
			Annotation annotation = annotations[j];
			if (annotation instanceof Concrete) {
				return true;
			}
		}
		return false;
	}

	/*
	 * MANAGEMENT METHODS
	 **/
	public class InnerManagement implements ManagementCallback, MethodInterceptor {

		@Override
		public List<Instance> getInstances() {
			Iterator<String> keys = instances.keySet().iterator();

			List<Instance> instanceList = new ArrayList<Instance>();
			while (keys.hasNext()) {
				String key = keys.next();
				instanceList.add(instances.get(key));
			}
			Collections.sort(instanceList, new Comparator<Instance>() {
				@Override
				public int compare(Instance o1, Instance o2) {
					if(o1.getCreationTimeStamp() < o2.getCreationTimeStamp()){
						return -1;
					}
					if(o1.getCreationTimeStamp() > o2.getCreationTimeStamp()){
						return 1;
					}
					return 0;
				}
			});
			return instanceList;
		}

		@Override
		public Instance getInstance(String refId) {
			if (refId.equals(DEFAULT_BASE_INSTANCE_ID)) {
				return defaultBaseInstance;
			}
			String id = instancesRef.get(refId);
			return instances.get(id);
		}

		@Override
		public void updateModel(String instanceRefId, Boolean applyToRunningInstances, Actions actions) {
			if (instanceRefId.equals(DEFAULT_BASE_INSTANCE_ID)) {
				lockUpdates.lock();
				try {
					String newVersion = versionManager.updateVersion();
					defaultBaseInstance.updateModel(actions, newVersion);
					if (applyToRunningInstances) {
						Iterator<String> keys = instances.keySet().iterator();
						while (keys.hasNext()) {
							String key = keys.next();
							instances.get(key).updateModel(actions, newVersion);
						}

						for (Instance instance : onGoingInstances) {
							instance.updateModel(actions, newVersion);
						}
					}
				} finally {
					lockUpdates.unlock();
				}
			} else {
				Instance instance = instances.get(instancesRef.get(instanceRefId));
				instance.updateModel(actions);
			}
		}

		@Override
		public void updateOrchestrationInterface(String instanceRefId, Boolean applyForAllInstances, MethodsInfo methodsInfo) {
				if (instanceRefId.equals(DEFAULT_BASE_INSTANCE_ID)) {
					lockUpdates.lock();
					try {
						defaultBaseInstance.updateOrchestrationInterface(methodsInfo);
						if (applyForAllInstances) {
							Iterator<String> keys = instances.keySet().iterator();
							while (keys.hasNext()) {
								String key = keys.next();
								instances.get(key).updateOrchestrationInterface(methodsInfo);
							}
							
							for(Instance instance:onGoingInstances){
								instance.updateOrchestrationInterface(methodsInfo);
							}
						}
					} finally {
						lockUpdates.unlock();
					}

				} else {
					String id = instancesRef.get(instanceRefId);
					if (id == null) {
						throw new RuntimeException("Instance not found");
					}
					instances.get(id).updateOrchestrationInterface(methodsInfo);
				}
		}		
		
		@Override
		public void disableAction(String action) {
			defaultBaseInstance.disableAction(action);			
		}

		@Override
		public Object intercept(Object object, Method method, 
								Object[] args, MethodProxy proxy) throws Throwable {
			
			if (method.equals(ManagementCallback.class
					.getMethod("getInstances"))) {
				return getInstances();
			} else if (method.equals(ManagementCallback.class.getMethod(
					"getInstance", String.class))) {
				return getInstance((String) args[0]);
			} else if (method.equals(ManagementCallback.class.getMethod(
					"updateModel",String.class, Boolean.class, Actions.class))) {
				updateModel((String)args[0], (Boolean) args[1], (Actions) args[2]);
			} else if (method.equals(ManagementCallback.class.getMethod(
					"disableAction", String.class))) {
				disableAction((String) args[0]);
			} else if (method.equals(ManagementCallback.class.getMethod(
					"updateOrchestrationInterface", String.class, Boolean.class, MethodsInfo.class))) {
				updateOrchestrationInterface((String) args[0],(Boolean) args[1],(MethodsInfo) args[2]);
			}
			return null;
		}

	}
}