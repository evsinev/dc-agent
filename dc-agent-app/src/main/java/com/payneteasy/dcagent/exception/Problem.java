package com.payneteasy.dcagent.exception;

import lombok.Data;

@Data
public class Problem {

    private final String type;
    private final String title;
    private final String errorId;

}
