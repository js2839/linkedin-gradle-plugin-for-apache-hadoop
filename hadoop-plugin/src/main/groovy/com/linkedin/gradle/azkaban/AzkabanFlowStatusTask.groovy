/*
 * Copyright 2015 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.gradle.azkaban;

import com.linkedin.gradle.azkaban.client.AzkabanClient;
import com.linkedin.gradle.azkaban.client.AzkabanStatus;
import com.linkedin.gradle.util.AzkabanClientUtil;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AzkabanFlowStatusTask fetches the status of flows in Azkaban.
 */
class AzkabanFlowStatusTask extends DefaultTask {

  AzkabanProject azkProject;
  List<String> flowsToExecute;

  /**
   * The Gradle task action for getting flowStatus from Azkaban.
   */
  @TaskAction
  void status() {
    if (azkProject.azkabanUrl == null) {
      throw new GradleException("Please set azkaban.url in the .azkabanPlugin.json file in your project directory.");
    }
    getAzkabanFlowStatus(AzkabanHelper.readSession(azkProject.azkabanUrl));
  }

  /**
   * Returns the list of flows defined in the project.
   *
   * @param sessionId The session ID of the session
   * @return The list of all the flows defined in the project
   */
  List<String> getSortedFlows(String sessionId) {
    // Fetch the project flows
    sessionId = AzkabanHelper.resumeOrGetSession(sessionId, azkProject);
    String fetchFlowsResponse = AzkabanClient.fetchProjectFlows(azkProject.azkabanUrl, azkProject.azkabanProjName, sessionId);

    if (fetchFlowsResponse.toLowerCase().contains("error")) {
      // Check if session has expired. If so, re-login.
      if (fetchFlowsResponse.toLowerCase().contains("session")) {
        logger.lifecycle("Previous Azkaban session expired. Please re-login.");
        return getSortedFlows(null);
      }

      // If response contains other than session error
      String msg = "Fetching flows from " + azkProject.azkabanUrl + " failed. Reason: " + new JSONObject(fetchFlowsResponse).get("error");
      throw new GradleException(msg);
    }

    return AzkabanHelper.fetchSortedFlows(new JSONObject(fetchFlowsResponse));
  }

  /**
   * This function returns the most recent execution ID for each flow.
   *
   * @param sessionId The session ID of the session
   * @param flows The list of flows defined in the project
   * @return The list of most recent execution ID for each flow
   */
  List<String> getRecentFlowExecutionIds(String sessionId, List<String> flows) {
    // Pool HTTP GET requests for getting most recent ExecID for each flow
    List<String> responseList = AzkabanClient.batchFetchLatestExecution(azkProject.azkabanUrl, azkProject.azkabanProjName, flows, sessionId);
    List<String> execIds = new ArrayList<String>();

    for (String execResponse : responseList) {
      JSONObject execIDResponse = new JSONObject(execResponse);

      if (execIDResponse.has("error")) {
        logger.error("Could not get flow due to " + execIDResponse.get("error").toString());
      } else if (execIDResponse.has("executions")) {
        JSONArray lastExecArray = execIDResponse.getJSONArray("executions");
        if (lastExecArray != null && !lastExecArray.isNull(0)) {
          JSONObject lastExecObj = new JSONObject(lastExecArray.get(0).toString());
          execIds.add(lastExecObj.get("execId").toString());
        }
      }
    }
    return execIds;
  }

  /**
   * Gets the status of the flows.
   *
   * @param sessionId The Azkaban session ID. If this is null, an attempt will be made to login to Azkaban.
   */
  void getAzkabanFlowStatus(String sessionId) {
    // List of all the flows defined in the project
    List<String> flows = getSortedFlows(sessionId);

    // Check if there are no flows in the project
    if (flows.isEmpty()) {
      logger.lifecycle("No flows defined in current project");
      return;
    }

    // The user may have had to re-login to Azkaban, so re-read the session ID
    sessionId = AzkabanHelper.readSession(azkProject.azkabanUrl)

    // List of all the recent execution ID's of the flows defined in the project
    List<String> execIds = getRecentFlowExecutionIds(sessionId, flows);

    // Check if there are no previous executions for the flows in the project
    if (execIds.isEmpty()) {
      logger.lifecycle("Project ${azkProject.azkabanProjName} has no previously executed flows.");
      return;
    }

    // Pool HTTP GET requests for getting exec Job statuses
    List<String> responseList = AzkabanClient.batchFetchFlowExecution(azkProject.azkabanUrl, execIds, sessionId);

    if (project.hasProperty("flow")) {
      if (project.getProperties().get("flow").toString().isEmpty()) {
        jobStatus(flows, responseList);
      } else {
        List<String> sortedFlowSet = new ArrayList(new HashSet<String>(Arrays.asList(project.getProperties().get("flow").toString().split(",+"))));
        Collections.sort(sortedFlowSet);
        jobStatus(sortedFlowSet, responseList);
      }
    } else if (flowsToExecute != null && !flowsToExecute.isEmpty()) {
      // flowsToExecute is defined by the task
      flowStatus(flowsToExecute, responseList);
    } else {
      // flowsToExecute is not defined by the task and flow property is not defined. Fetch all flows
      flowStatus(flows, responseList);
    }
  }

  /**
   * Prints the job status of the latest flow executions.
   *
   * @param flows The list of flows for which status should be fetched
   * @param responseList The response list from Azkaban
   */
  void jobStatus(List<String> flows, List<String> responseList) {
    flows.each { flow ->
      for (int j = 0; j < responseList.size(); j++) {
        String jobResponse = responseList.get(j);
        JSONObject jobsObject = new JSONObject(jobResponse);

        if (flow.equals(jobsObject.get("flow"))) {
          String execid = jobsObject.get("execid").toString();
          String flowStatus = jobsObject.get("status").toString();
          String flowSubmitTime = AzkabanClientUtil.epochToDate(jobsObject.get("submitTime").toString());
          String flowStartTime = AzkabanClientUtil.epochToDate(jobsObject.get("startTime").toString());
          String flowEndTime = AzkabanClientUtil.epochToDate(jobsObject.get("endTime").toString());
          String flowElapsedTime = AzkabanClientUtil.getElapsedTime(jobsObject.get("startTime").toString(), jobsObject.get("endTime").toString());

          System.out.println("\nFlow: ${flow} | Exec Id: ${execid} | Status: ${flowStatus} | Submitted: ${flowSubmitTime} | Started: ${flowStartTime} | Ended : ${flowEndTime} | Elapsed: ${flowElapsedTime}");
          System.out.println("---------------------------------------------------------------------------------------------------------------------------------------------");
          AzkabanHelper.printJobStats("JOB NAME", "JOB TYPE", "JOB STATUS", "JOB START TIME", "JOB END TIME", "ELAPSED");
          System.out.println("---------------------------------------------------------------------------------------------------------------------------------------------");

          if (jobsObject.has("nodes")) {
            JSONArray jobArray = jobsObject.getJSONArray("nodes");
            if (jobArray != null) {
              for (int i = 0; i < jobArray.length(); i++) {
                JSONObject job = new JSONObject(jobArray.get(i).toString());
                String jobName = job.get("id").toString();
                String jobStatus = job.get("status").toString();
                String jobStartTime = AzkabanClientUtil.epochToDate(job.get("startTime").toString());
                String jobEndTime = AzkabanClientUtil.epochToDate(job.get("endTime").toString());
                String jobType = job.get("type").toString();
                String jobElapsedTime = AzkabanClientUtil.getElapsedTime(job.get("startTime").toString(), job.get("endTime").toString());
                AzkabanHelper.printJobStats(jobName, jobType, jobStatus, jobStartTime, jobEndTime, jobElapsedTime);
              }
            }
          }
          System.out.println("---------------------------------------------------------------------------------------------------------------------------------------------");
          printUrls(execid);
          break;
        }

        if (j == responseList.size() - 1) {
          System.out.println("\nFlow: ${flow}\n---------------------------------------------------------------------------------------------------------------------------------------------");
          System.out.print("\tNo execution yet.\n");
          System.out.println("---------------------------------------------------------------------------------------------------------------------------------------------");
        }
      }
    }
  }

  /**
   * Prints the status of the latest execution of each flow in the project.
   *
   * @param flows The list of flows for which status should be fetched
   * @param responseList The response list from Azkaban
   */
  void flowStatus(List<String> flows, List<String> responseList) {
    // Print the flow status
    System.out.println("\nJob Statistics for individual flow\n------------------------------------------------------------------------------------------------------------------------------------------------");
    AzkabanHelper.printFlowStats("Flow Name", "Latest Exec ID", "Status", AzkabanStatus.getStatusLabels());
    System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------");

    flows.each { flow ->
      boolean hasAnyExecutions = false;

      for (int j = 0; j < responseList.size(); j++) {
        String jobResponse = responseList.get(j);
        JSONObject jobsObject = new JSONObject(jobResponse);

        if (flow.equals(jobsObject.get("flow"))) {
          String execid = jobsObject.get("execid");
          String status = jobsObject.get("status");
          AzkabanStatus azkStatus = new AzkabanStatus();

          if (jobsObject.has("nodes")) {
            JSONArray jobArray = jobsObject.getJSONArray("nodes");
            if (jobArray != null) {
              for (int i = 0; i < jobArray.length(); i++) {
                JSONObject job = new JSONObject(jobArray.get(i).toString());
                String jobStatus = job.get("status").toString();
                azkStatus.increment(jobStatus);
              }
            }
          }

          AzkabanHelper.printFlowStats(flow, execid, status, azkStatus.getStatusValues());
          hasAnyExecutions = true;
          break;
        }
      }

      if (!hasAnyExecutions) {
        AzkabanHelper.printFlowStats(flow, "NONE", "-", AzkabanStatus.getStatusLabels());
      }
    }

    System.out.println("------------------------------------------------------------------------------------------------------------------------------------------------");
  }

  /**
   * Builds the URL to Dr. Elephant for a given flow.
   *
   * @param execUrl The flow execution URL
   * @return The URL to Dr. Elephant for the given flow
   */
  String getDrElephantURL(String execUrl) {
    return "/search?flow-exec-id=" + URLEncoder.encode(execUrl, "UTF-8").toString();
  }

  /**
   * Prints URL's of Azkaban Execution and Dr. Elephant Report
   *
   * @param execId Execution Id of the flow
   */
  void printUrls(String execId) {
    String execUrl = azkProject.azkabanUrl + "/executor?execid=${execId}";
    logger.lifecycle("Execution URL: ${execUrl}");

    String DrElephantUrl = getDrElephantURL(execUrl);
    if (DrElephantUrl != null) {
      logger.lifecycle("Dr. Elephant URL: ${DrElephantUrl}");
    }
  }
}