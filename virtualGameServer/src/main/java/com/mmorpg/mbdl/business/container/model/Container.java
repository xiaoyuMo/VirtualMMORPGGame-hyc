package com.mmorpg.mbdl.business.container.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.TreeMultimap;
import com.mmorpg.mbdl.business.container.exception.ItemNotEnoughException;
import com.mmorpg.mbdl.business.container.manager.ContainerManager;
import com.mmorpg.mbdl.business.container.res.ItemRes;
import com.mmorpg.mbdl.framework.common.utils.JsonUtil;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;

/**
 * 容器
 *
 * @author Sando Geek
 * @since v1.0 2019/1/15
 **/
public class Container {
    private Map<Long, Item> id2ItemMap = new HashMap<>();
    /**
     * 内部维护的用于合并可合并物品的数据结构
     */
    @JsonIgnore
    private TreeMultimap<Integer, Item> key2ItemMultiMap = TreeMultimap.create();

    /**
     * 添加物品
     * @param key 配置表中的
     * @param amount 数量
     * @return 添加成功返回true，失败返回false
     */
    public boolean addItem(int key, int amount) {
        ItemRes itemRes = ContainerManager.getInstance().getItemResByKey(key);
        int maxAmount = itemRes.getMaxAmount();
        if (maxAmount == 1) {
            for (int i = 0; i < amount; i++) {
                doAddItem(new Item(key,1));
            }
            return true;
        }
        addItemHelper1(key, amount, maxAmount);
        return true;
    }

    private void addItemHelper1(int key, int amount, int maxAmount) {
        Item item = new Item(key, amount);
        // 找到id最新的同类物品，判断其是否达到最大堆叠数，没达到就把物品放到这个物品上
        NavigableSet<Item> items = key2ItemMultiMap.get(item.getKey());
        if (!items.isEmpty()) {
            // 自动排序了，最后一个是id最大（最新）的
            Item lastItem = Iterables.getLast(items);
            // 最后一个物品的剩余空间
            int amountLeft = maxAmount - lastItem.getAmount();
            // 还可以继续放入
            if (amountLeft > 0) {
                // 判断物品剩余空间能不能完全容纳此物品
                if (amountLeft >= item.getAmount()) {
                    // 可以完全容下，直接变更最新物品的数量即可
                    lastItem.setAmount(lastItem.getAmount() + item.getAmount());
                } else {
                    lastItem.setAmount(maxAmount);
                    addItemHelper1(key, amount - amountLeft,maxAmount);
                }
            } else {
                addItemHelper2(key, amount, item, maxAmount);
            }
        } else {
            addItemHelper2(key, amount, item, maxAmount);
        }
    }

    private void addItemHelper2(int key, int amount, Item item, int maxAmount) {
        if (amount > maxAmount) {
            item.setAmount(maxAmount);
            doAddItem(item);
            addItemHelper1(key,amount - maxAmount,maxAmount);
        } else {
            doAddItem(item);
        }
    }

    private void doAddItem(Item item) {
        item.init();
        id2ItemMap.put(item.getObjectId(), item);
        key2ItemMultiMap.put(item.getKey(), item);
    }

    /**
     * 根据配置表key，删除物品，通常可堆叠的物品属性是一致的，有随机属性的物品不可堆叠，所以这个函数只能用在可堆叠物品上
     * @param key 物品配置表key
     * @param amount 删除数量
     * @exception com.mmorpg.mbdl.business.container.exception.ItemNotEnoughException 物品数量不足
     */
    public boolean removeItem(int key, int amount) {
        ItemRes itemRes = ContainerManager.getInstance().getItemResByKey(key);
        int maxAmount = itemRes.getMaxAmount();
        if (maxAmount == 1) {
            throw new RuntimeException("不可堆叠物品（上限为1）不可使用此函数扣除");
        }
        NavigableSet<Item> items = key2ItemMultiMap.get(key);
        if (items.isEmpty()) {
            throw new ItemNotEnoughException("物品数量不足");
        }
        Integer sum = items.stream().map(Item::getAmount).reduce(0, Integer::sum);
        if (amount > sum) {
            throw new ItemNotEnoughException("物品数量不足");
        }
        removeHelper(key,amount,items);
        return true;
    }

    private void removeHelper(int key, int amount, NavigableSet<Item> items) {
        Item lastItem = Iterables.getLast(items);
        int lack = lastItem.getAmount() - amount;
        // 最后一个物品够用
        if (lack > 0) {
            lastItem.setAmount(lack);
        } else if (lack == 0) {
            doRemoveItem(lastItem);
        } else {
            // 不够用
            doRemoveItem(lastItem);
            removeHelper(key,-lack,key2ItemMultiMap.get(key));
        }
    }

    public boolean removeItem(long objectId, int amount) {
        Item item = id2ItemMap.get(objectId);
        if (item == null) {
            return false;
        }
        ItemRes itemRes = ContainerManager.getInstance().getItemResByKey(item.getKey());
        int maxAmount = itemRes.getMaxAmount();
        if (maxAmount == 1) {
            doRemoveItem(item);
        } else {
            removeItem(item.getKey(),amount);
        }
        return true;
    }

    private void doRemoveItem(Item item) {
        id2ItemMap.remove(item.getObjectId());
        key2ItemMultiMap.remove(item.getKey(),item);
    }

    public Collection<Item> getAll() {
        return id2ItemMap.values();
    }

    public Container setId2ItemMap(Map<Long, Item> id2ItemMap) {
        id2ItemMap.values().forEach(item -> key2ItemMultiMap.put(item.getKey(), item));
        this.id2ItemMap = id2ItemMap;
        return this;
    }

    public Multimap<Integer, Item> getKey2ItemMultiMap() {
        return key2ItemMultiMap;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id2ItemMap", JsonUtil.object2String(id2ItemMap))
                .toString();
    }
}
