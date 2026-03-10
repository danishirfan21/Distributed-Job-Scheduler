package com.distributed.jobscheduler.scheduler.service;

import com.distributed.jobscheduler.scheduler.entity.JobEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class DAGValidationService {

    public boolean validateDAG(JobEntity job, Map<String, JobEntity> allJobs) {
        if (job.getDependencies() == null || job.getDependencies().isEmpty()) {
            return true;
        }

        // Check for cycles using DFS
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        return !hasCycle(job.getId(), allJobs, visited, recursionStack);
    }

    private boolean hasCycle(String jobId, Map<String, JobEntity> allJobs,
                            Set<String> visited, Set<String> recursionStack) {

        visited.add(jobId);
        recursionStack.add(jobId);

        JobEntity currentJob = allJobs.get(jobId);
        if (currentJob != null && currentJob.getDependencies() != null) {
            for (String dependencyId : currentJob.getDependencies()) {
                // Check if dependency exists
                if (!allJobs.containsKey(dependencyId)) {
                    log.warn("Job {} depends on non-existent job: {}", jobId, dependencyId);
                    return true; // Treat missing dependency as cycle
                }

                // If not visited, recurse
                if (!visited.contains(dependencyId)) {
                    if (hasCycle(dependencyId, allJobs, visited, recursionStack)) {
                        return true;
                    }
                }
                // If in recursion stack, we found a cycle
                else if (recursionStack.contains(dependencyId)) {
                    log.error("Cycle detected: {} -> {}", jobId, dependencyId);
                    return true;
                }
            }
        }

        recursionStack.remove(jobId);
        return false;
    }

    public List<String> getTopologicalOrder(Collection<JobEntity> jobs) {
        Map<String, JobEntity> jobMap = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();

        // Initialize structures
        for (JobEntity job : jobs) {
            String id = job.getId();
            jobMap.put(id, job);
            inDegree.put(id, 0);
            adj.put(id, new ArrayList<>());
        }

        // Build adjacency list (dep -> dependents) and calculate in-degrees
        for (JobEntity job : jobs) {
            if (job.getDependencies() != null) {
                for (String depId : job.getDependencies()) {
                    if (jobMap.containsKey(depId)) {
                        adj.get(depId).add(job.getId());
                        inDegree.put(job.getId(), inDegree.get(job.getId()) + 1);
                    }
                }
            }
        }

        // Kahn's algorithm
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String jobId = queue.poll();
            result.add(jobId);

            for (String neighborId : adj.get(jobId)) {
                inDegree.put(neighborId, inDegree.get(neighborId) - 1);
                if (inDegree.get(neighborId) == 0) {
                    queue.offer(neighborId);
                }
            }
        }

        return result;
    }

    public boolean areDependenciesCompleted(String jobId, Map<String, JobEntity> allJobs,
                                           Set<String> completedJobs) {
        JobEntity job = allJobs.get(jobId);
        if (job == null || job.getDependencies() == null || job.getDependencies().isEmpty()) {
            return true;
        }

        return completedJobs.containsAll(job.getDependencies());
    }
}
