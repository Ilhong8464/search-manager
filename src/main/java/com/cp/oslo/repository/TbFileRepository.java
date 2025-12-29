package com.cp.oslo.repository;

import com.cp.oslo.domain.TbFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TbFileRepository extends JpaRepository<TbFile, Long> {

    Optional<TbFile> findByFileUuid(String fileUuid);

    // 인덱싱 가능한 파일 타입만 조회
    @Query("SELECT f FROM TbFile f WHERE f.contentType IN :contentTypes AND f.srcId1 IN :srcId1List")
    List<TbFile> findIndexableFilesByContentTypesAndSrcId1In(
            @Param("contentTypes") List<String> contentTypes,
            @Param("srcId1List") List<String> srcId1List);
}
