package com.cxalloy.integration.dto;

import java.util.ArrayList;
import java.util.List;

public class CopilotChatRequest {

    private String apiKey;
    private String model;
    private String prompt;
    private List<String> projectIds = new ArrayList<>();
    private List<CopilotMessage> conversation = new ArrayList<>();

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public List<String> getProjectIds() {
        return projectIds;
    }

    public void setProjectIds(List<String> projectIds) {
        this.projectIds = projectIds == null ? new ArrayList<>() : projectIds;
    }

    public List<CopilotMessage> getConversation() {
        return conversation;
    }

    public void setConversation(List<CopilotMessage> conversation) {
        this.conversation = conversation == null ? new ArrayList<>() : conversation;
    }
}
