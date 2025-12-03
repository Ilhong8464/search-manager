package com.cp.oslo.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Table(name = "TB_FILE")
@Getter
@ToString
@NoArgsConstructor
public class TbFile {

    @Id
    @Column(name = "FILE_ID")
    private Long fileId;

    @Column(name = "FILE_UUID")
    private String fileUuid;

    @Column(name = "COMPANY_CD")
    private String companyCd;

    @Column(name = "SRC_ID1")
    private String srcId1;

    @Column(name = "SRC_ID2")
    private String srcId2;

    @Column(name = "FILE_NM")
    private String fileNm;

    @Column(name = "URL")
    private String url;

    @Column(name = "SAVED_FILE_PATH")
    private String savedFilePath;

    @Column(name = "SAVED_FILE_NM")
    private String savedFileNm;

    @Column(name = "CONTENT_TYPE")
    private String contentType;

    @Column(name = "CONTENT_LENGTH")
    private Long contentLength;

    @Column(name = "RGTR_ID")
    private String rgtrId;

    @Column(name = "REG_DT")
    private LocalDateTime regDt;

    @Column(name = "REG_YMD")
    private String regYmd;
}
