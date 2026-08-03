package com.midhudsonfiber.inventory.service;

import com.midhudsonfiber.inventory.domain.Asset;
import com.midhudsonfiber.inventory.web.dto.AssetRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one imported asset inside its own transaction.
 *
 * <p>This exists for one reason, and it is not stylistic. {@code AssetService.create}
 * is transactional, so calling it inside the import's transaction joins it. A row
 * that fails — a category with a required custom field, a serial that collides
 * with one created moments earlier in the same commit — marks the shared
 * transaction rollback-only. The import catches the exception and carries on,
 * but the transaction is already doomed, and the whole commit dies at the end
 * with "Transaction silently rolled back", taking every good row with it.
 *
 * <p>That is precisely the promise the feature makes backwards: a bad row is
 * supposed to be skipped, not to destroy the batch. {@code REQUIRES_NEW} gives
 * each row a transaction of its own, so a failure rolls back that row alone.
 *
 * <p>It has to be a separate bean. A method on {@code ImportService} calling
 * itself would bypass the proxy and get no new transaction at all — the bug
 * would look fixed and would not be.
 */
@Component
public class ImportRowCommitter {

    private final AssetService assets;

    public ImportRowCommitter(AssetService assets) {
        this.assets = assets;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Asset create(AssetRequest request) {
        // Not strict: an import is getting existing inventory into the system,
        // where refusing a row over a blank optional field just means the asset
        // goes untracked. The form still insists; see AssetService.create.
        return assets.create(request, false);
    }
}
