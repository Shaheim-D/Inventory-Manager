package com.midhudsonfiber.inventory.repo;

import com.midhudsonfiber.inventory.domain.Attachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByAssetIdOrderByUploadedAtDesc(Long assetId);

    List<Attachment> findByPurchaseOrderIdOrderByUploadedAtDesc(Long purchaseOrderId);
}
