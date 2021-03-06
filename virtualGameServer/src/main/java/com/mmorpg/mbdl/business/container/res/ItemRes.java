package com.mmorpg.mbdl.business.container.res;

import com.mmorpg.mbdl.business.container.model.ItemType;
import com.mmorpg.mbdl.business.equip.model.EquipType;
import com.mmorpg.mbdl.business.role.model.prop.PropType;
import com.mmorpg.mbdl.framework.resource.annotation.Key;
import com.mmorpg.mbdl.framework.resource.annotation.ResDef;

import java.util.Map;

/**
 * 物品配置表
 *
 * @author Sando Geek
 * @since v1.0 2019/1/25
 **/
@ResDef
public class ItemRes {
    @Key
    private int key;
    private String name;
    private Map<PropType,Long> propChangeAfterUse;
    private int maxAmount;
    private ItemType itemType;
    /** 初始耐久度 */
    private int durability;
    /**
     * 装备类型
     * @return
     */
    private EquipType equipType;

    public int getKey() {
        return key;
    }

    public String getName() {
        return name;
    }

    public int getMaxAmount() {
        return maxAmount;
    }

    public ItemType getItemType() {
        return itemType;
    }

    public int getDurability() {
        return durability;
    }

    public EquipType getEquipType() {
        return equipType;
    }

    public Map<PropType, Long> getPropChangeAfterUse() {
        return propChangeAfterUse;
    }
}
