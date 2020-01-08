package cn.batchfile.stat.server.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.TopContainerResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.ContainerPort;

import cn.batchfile.stat.server.dao.ContainerRepository;
import cn.batchfile.stat.server.domain.container.ContainerInstance;
import cn.batchfile.stat.server.domain.container.MountInstance;
import cn.batchfile.stat.server.domain.container.PortInstance;
import cn.batchfile.stat.server.domain.node.Node;
import cn.batchfile.stat.server.domain.service.Resources;
import cn.batchfile.stat.server.domain.service.ResourcesControl;
import cn.batchfile.stat.server.dto.ContainerTable;

@org.springframework.stereotype.Service
public class ContainerService {
	private static final Logger LOG = LoggerFactory.getLogger(ContainerService.class);
	
	@Autowired
	private ContainerRepository containerRepository;
	
	@Autowired
	private NodeService nodeService;
	
	@Autowired
	private DockerService dockerService;
	
	@Transactional(isolation = Isolation.READ_UNCOMMITTED, readOnly = true)
	public List<ContainerInstance> getContainersByService(String namespace, String serviceName) {
		LOG.debug("get containers of servie: {}/{}", namespace, serviceName);
		List<ContainerInstance> containers = new ArrayList<ContainerInstance>();
		Iterable<ContainerTable> it = containerRepository.findMany(namespace, serviceName);
		it.forEach(i -> {
			containers.add(compose(i));
		});
		LOG.debug("containers count: {}", containers.size());
		return containers;
	}
	
	@Transactional(isolation = Isolation.READ_UNCOMMITTED, readOnly = true)
	public List<ContainerInstance> getContainersByNode(String nodeId) {
		List<ContainerInstance> containers = new ArrayList<ContainerInstance>();
		Iterable<ContainerTable> it = containerRepository.findMany(nodeId);
		it.forEach(i -> {
			containers.add(compose(i));
		});
		LOG.debug("containers count: {}", containers.size());
		return containers;
	}
	
	@Transactional(isolation = Isolation.READ_UNCOMMITTED, readOnly = true)
	public ContainerInstance getContainer(String id) {
		LOG.debug("get container, id: {}", id);
		Optional<ContainerTable> op = containerRepository.findById(id);
		if (op.isPresent()) {
			return compose(op.get());
		} else {
			return null;
		}
	}
	
	public InspectContainerResponse getContainerInspect(String id) {
		ContainerInstance container = getContainer(id);
		Node node = nodeService.getNode(container.getNode());
		DockerClient docker = dockerService.getDockerClient(node.getInfo().getDockerHost(), node.getApiVersion());
		return docker.inspectContainerCmd(id).exec();
	}
	
	public TopContainerResponse getContainerTop(String id) {
		ContainerInstance container = getContainer(id);
		Node node = nodeService.getNode(container.getNode());
		DockerClient docker = dockerService.getDockerClient(node.getInfo().getDockerHost(), node.getApiVersion());
		return docker.topContainerCmd(id).exec();
	}
	
	public void startContainer(String id) {
		ContainerInstance container = getContainer(id);
		Node node = nodeService.getNode(container.getNode());
		DockerClient docker = dockerService.getDockerClient(node.getInfo().getDockerHost(), node.getApiVersion());
		docker.startContainerCmd(id).exec();
	}
	
	public void stopContainer(String id) {
		ContainerInstance container = getContainer(id);
		Node node = nodeService.getNode(container.getNode());
		DockerClient docker = dockerService.getDockerClient(node.getInfo().getDockerHost(), node.getApiVersion());
		docker.stopContainerCmd(id).exec();
	}
	
	public void removeContainer(String id) {
		ContainerInstance container = getContainer(id);
		Node node = nodeService.getNode(container.getNode());
		DockerClient docker = dockerService.getDockerClient(node.getInfo().getDockerHost(), node.getApiVersion());
		docker.removeContainerCmd(id).exec();
	}
	
	@Transactional(isolation = Isolation.READ_UNCOMMITTED)
	public List<ContainerInstance> refreshContainer(Node node) {
		String ip = node.getInfo().getPublicIp();
		DockerClient docker = dockerService.getDockerClient(node.getInfo().getDockerHost(), node.getApiVersion());
		List<Container> containers = docker.listContainersCmd().withShowAll(true).exec();

		List<ContainerInstance> oldList = getContainersByNode(node.getId());
		List<ContainerInstance> list = new ArrayList<>();
		for (Container container : containers) {
			//寻找老容器，在老容器的属性上设置新的值
			ContainerInstance ci = findInList(oldList, container.getId());
			if (ci == null) {
				ci = new ContainerInstance();
			}
			String oldState = ci.getState();
			
			//从接口数据中同步容器属性
			compose(ci, container);

			//同步节点属性
			ci.setNode(node.getId());
			ci.setIp(ip);
			
			//判断容器的状态
			refreshTime(ci, oldState);

			//构建数据对象
			ContainerTable ct = compose(ci);
			containerRepository.save(ct);
			
			list.add(ci);
		}
		
		//遍历老数据，判断是不是已经不在了
		for (ContainerInstance oldData : oldList) {
			if (findInList(list, oldData.getId()) == null) {
				LOG.info("delete container: {}", oldData.getId());
				containerRepository.deleteById(oldData.getId());
			}
		}
		
		//TODO 删除已经没有节点的容器
		
		return list;
	}
	
	private void refreshTime(ContainerInstance container, String oldState) {
		//如果从其他状态转入running状态，设置启动时间
		String newState = container.getState();
		if (!StringUtils.equals(oldState, "running") && StringUtils.equals(newState, "running")) {
			container.setStartTime(new Date());
		}

		//如果从running状态转入其他状态，设置停止时间
		if (StringUtils.equals(oldState, "running") && !StringUtils.equals(newState, "running")) {
			container.setStopTime(new Date());
		}
	}
	
	private ContainerInstance findInList(List<ContainerInstance> list, String id) {
		for (ContainerInstance obj : list) {
			if (StringUtils.equals(obj.getId(), id)) {
				return obj;
			}
		}
		return null;
	}
	
	private ContainerInstance compose(ContainerInstance ci, Container container) {
		ci.setId(container.getId());
		
		Resources res = new Resources();
		ResourcesControl limits = new ResourcesControl();
		ResourcesControl requests = new ResourcesControl();
		res.setLimits(limits);
		res.setRequests(requests);
		ci.setResources(res);
		
		Map<String, String> labels = container.getLabels();
		if (StringUtils.equals("__stat", labels.get("__creator"))) {
			ci.setNamespace(labels.get("__namespace"));
			ci.setService(labels.get("__service_name"));
			ci.setDomainNames(JSON.parseArray(labels.get("__domain_names"), String.class));
			limits.setCpus(Float.valueOf(labels.get("__limits_cpus")));
			limits.setMemory(labels.get("__limits_memory"));
			requests.setCpus(Float.valueOf(labels.get("__requests_cpus")));
			requests.setMemory(labels.get("__requests_memory"));
		} else {
			limits.setCpus(0F);
			limits.setMemory("0");
			requests.setCpus(0F);
			requests.setMemory("0");
		}
		
		ci.setName(container.getNames()[0]);
		ci.setImage(container.getImage());
		ci.setCommand(container.getCommand());
		
		List<PortInstance> ports = new ArrayList<PortInstance>();
		for (ContainerPort p : container.getPorts()) {
			PortInstance pi = new PortInstance();
			pi.setIp(p.getIp());
			pi.setPrivatePort(p.getPrivatePort());
			pi.setPublicPort(p.getPublicPort());
			pi.setType(p.getType());
			ports.add(pi);
		}
		ci.setPorts(ports);
		
		List<MountInstance> mounts = new ArrayList<MountInstance>();
		for (ContainerMount m : container.getMounts()) {
			MountInstance mi = new MountInstance();
			mi.setDestination(m.getDestination());
			mi.setMode(m.getMode());
			mi.setReadOnly(!m.getRw());
			mi.setSource(m.getSource());
			mi.setType(m.getDriver());
			mounts.add(mi);
		}
		ci.setMounts(mounts);
		
		ci.setState(container.getState());
		ci.setDescription(container.getStatus());
		ci.setCreateTime(new Date(container.getCreated() * 1000L));
		
		return ci;
	}
	
	private ContainerTable compose(ContainerInstance containerInstance) {
		ContainerTable containerTable = new ContainerTable();
		containerTable.setId(containerInstance.getId());
		containerTable.setNode(containerInstance.getNode());
		containerTable.setNamespace(containerInstance.getNamespace());
		containerTable.setService(containerInstance.getService());
		containerTable.setDomainNames(JSON.toJSONString(containerInstance.getDomainNames()));
		containerTable.setIp(containerInstance.getIp());
		containerTable.setName(containerInstance.getName());
		containerTable.setImage(containerInstance.getImage());
		containerTable.setCommand(containerInstance.getCommand());
		containerTable.setPorts(JSON.toJSONString(containerInstance.getPorts()));
		containerTable.setMounts(JSON.toJSONString(containerInstance.getMounts()));
		
		if (containerInstance.getResources() != null) {
			if (containerInstance.getResources().getLimits() != null) {
				containerTable.setLimitCpus(containerInstance.getResources().getLimits().getCpus());
				containerTable.setLimitMemory(containerInstance.getResources().getLimits().getMemory());
			}
			if (containerInstance.getResources().getRequests() != null) {
				containerTable.setRequestCpus(containerInstance.getResources().getRequests().getCpus());
				containerTable.setRequestMemory(containerInstance.getResources().getRequests().getMemory());
			}
		}
		
		containerTable.setState(containerInstance.getState());
		containerTable.setDescription(containerInstance.getDescription());
		containerTable.setCreateTime(containerInstance.getCreateTime());
		containerTable.setStartTime(containerInstance.getStartTime());
		containerTable.setStopTime(containerInstance.getStopTime());
		return containerTable;
	}

	private ContainerInstance compose(ContainerTable containerTable) {
		ContainerInstance container = new ContainerInstance();
		container.setId(containerTable.getId());
		container.setNode(containerTable.getNode());
		container.setNamespace(containerTable.getNamespace());
		container.setService(containerTable.getService());
		container.setDomainNames(JSON.parseArray(containerTable.getDomainNames(), String.class));
		container.setIp(containerTable.getIp());
		container.setName(containerTable.getName());
		container.setImage(containerTable.getImage());
		container.setCommand(containerTable.getCommand());
		container.setPorts(JSON.parseArray(containerTable.getPorts(), PortInstance.class));
		container.setMounts(JSON.parseArray(containerTable.getMounts(), MountInstance.class));
		
		Resources res = new Resources();
		ResourcesControl limits = new ResourcesControl();
		limits.setCpus(containerTable.getLimitCpus());
		limits.setMemory(containerTable.getLimitMemory());
		res.setLimits(limits);
		
		ResourcesControl requests = new ResourcesControl();
		requests.setCpus(containerTable.getRequestCpus());
		requests.setMemory(containerTable.getRequestMemory());
		res.setRequests(requests);
		
		container.setResources(res);
		container.setState(containerTable.getState());
		container.setDescription(containerTable.getDescription());
		container.setCreateTime(containerTable.getCreateTime());
		container.setStartTime(containerTable.getStartTime());
		container.setStopTime(containerTable.getStopTime());
		return container;
	}
}
