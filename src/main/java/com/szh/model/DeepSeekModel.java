package com.szh.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.szh.context.dto.AssistantMessageItem;
import com.szh.context.dto.MessageItem;
import com.szh.context.dto.ToolMessageItem;
import com.szh.model.dto.ActionEnum;
import com.szh.model.dto.ModelResp;
import com.szh.tool.ToolDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;


public class DeepSeekModel implements Model {


    private static final String API_URL =
            "https://api.deepseek.com/chat/completions";


    private final String apiKey;


    private final ObjectMapper mapper =
            new ObjectMapper();


    private final HttpClient client =
            HttpClient.newHttpClient();



    public DeepSeekModel(String apiKey){

        this.apiKey = apiKey;

    }



    @Override
    public ModelResp call(
            List<MessageItem> messages,
            List<ToolDefinition> tools
    ){

        try {


            ObjectNode request =
                    mapper.createObjectNode();


            request.put(
                    "model",
                    "deepseek-chat"
            );


            /*
             * MessageItem转换成DeepSeek messages
             */
            request.set(
                    "messages",
                    convertMessages(messages)
            );


            /*
             * 工具定义
             */
            if(tools != null &&
                    !tools.isEmpty()){


                request.set(
                        "tools",
                        convertTools(tools)
                );


                request.put(
                        "tool_choice",
                        "auto"
                );

            }



            HttpRequest httpRequest =
                    HttpRequest.newBuilder()
                            .uri(
                                    URI.create(API_URL)
                            )
                            .header(
                                    "Content-Type",
                                    "application/json"
                            )
                            .header(
                                    "Authorization",
                                    "Bearer "+apiKey
                            )
                            .POST(
                                    HttpRequest.BodyPublishers
                                            .ofString(
                                                    request.toString()
                                            )
                            )
                            .build();



            HttpResponse<String> response =
                    client.send(
                            httpRequest,
                            HttpResponse.BodyHandlers.ofString()
                    );



            if(response.statusCode()!=200){

                throw new RuntimeException(
                        "deepseek error:"
                                + response.body()
                );

            }



            return parseResponse(response.body());
        }catch(Exception e){

            throw new RuntimeException("DeepSeek call failed", e);

        }

    }





    /**
     * MessageItem
     *
     * 转成DeepSeek messages
     */
    private ArrayNode convertMessages(
            List<MessageItem> items
    ){

        ArrayNode array = mapper.createArrayNode();

        for(MessageItem item:items){

            ObjectNode message =
                    mapper.createObjectNode();
            message.put("role", item.role());
            message.put(
                    "content",
                    item.transfer2prompt()
            );

            if (item instanceof AssistantMessageItem assistantItem && assistantItem.isCallTool()) {
                message.putNull("content");
                ArrayNode toolCallsArray = mapper.createArrayNode();
                ObjectNode toolCall = mapper.createObjectNode();
                toolCall.put("id", assistantItem.getToolCallId());
                toolCall.put("type", "function");
                ObjectNode function = mapper.createObjectNode();
                function.put("name", assistantItem.getToolCode());
                function.put("arguments", assistantItem.getToolArgs());
                toolCall.set("function", function);
                toolCallsArray.add(toolCall);
                message.set("tool_calls", toolCallsArray);
            }

            if (item instanceof ToolMessageItem toolItem) {
                message.put("tool_call_id", toolItem.getCallId());
            }

            array.add(message);

        }
        return array;

    }



    /**
     * Tool Definition
     *
     * 转DeepSeek function schema
     */
    private ArrayNode convertTools(
            List<ToolDefinition> tools
    ) throws Exception{


        ArrayNode array = mapper.createArrayNode();


        for(ToolDefinition tool:tools){


            ObjectNode function =
                    mapper.createObjectNode();



            function.put(
                    "name",
                    tool.getName()
            );


            function.put(
                    "description",
                    tool.getDescription()
            );


            function.set(
                    "parameters",
                    mapper.readTree(
                            tool.getParameters()
                    )
            );



            ObjectNode item =
                    mapper.createObjectNode();


            item.put(
                    "type",
                    "function"
            );


            item.set(
                    "function",
                    function
            );


            array.add(item);

        }


        return array;

    }






    /**
     * DeepSeek Response
     *
     * 转AssistantMessageItem
     */
    private ModelResp parseResponse(
            String json
    ) throws Exception{


        JsonNode root = mapper.readTree(json);
        JsonNode message = root.path("choices").get(0).path("message");


        /*
         * 工具调用
         */
        JsonNode toolCalls = message.get("tool_calls");

        if(toolCalls!=null && toolCalls.size()>0){
            JsonNode function = toolCalls.get(0).get("function");
            String toolName = function.get("name").asText();
            String arguments = function.get("arguments").asText();
            String toolCallId = toolCalls.get(0).get("id").asText();
            AssistantMessageItem assistantMessageItem = new AssistantMessageItem(
                    toolCallId,
                    toolName,
                    arguments
            );

            return new ModelResp(
                    assistantMessageItem,
                    ActionEnum.TOOL_CALL
            );

        }

        /*
         * 普通回答
         */
        return new ModelResp(
                new AssistantMessageItem(message.get("content").asText()),
                ActionEnum.FINAL_ANSWER
        );

    }

}