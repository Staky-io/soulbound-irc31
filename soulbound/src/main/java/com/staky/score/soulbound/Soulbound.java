/*
 * Copyright 2021 Sharpn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.staky.score.soulbound;

import com.iconloop.score.token.irc31.IRC31;
import score.Address;
import score.BranchDB;
import score.ByteArrayObjectWriter;
import score.Context;
import score.DictDB;
import score.annotation.EventLog;
import score.annotation.External;

import java.math.BigInteger;

public class Soulbound {

    // ================================================
    // Consts
    // ================================================
    public static final Address ZERO_ADDRESS = new Address(new byte[Address.LENGTH]);

    // ================================================
    // SCORE DB
    // ================================================
    // id => (owner => balance)
    private final BranchDB<BigInteger, DictDB<Address, BigInteger>> balances = Context.newBranchDB("balances", BigInteger.class);
    // owner => (operator => approved)
    // id => token URI
    private final DictDB<BigInteger, String> tokenURIs = Context.newDictDB("token_uri", String.class);
    private final DictDB<Address, Boolean> admins = Context.newDictDB("admins", Boolean.class);

    public Soulbound() {
        admins.set(Context.getOwner(), true);
    }

    // ================================================
    // Utils
    // ================================================

    protected void checkOwnerOrThrow() {
        if (!Context.getCaller().equals(Context.getOwner())) {
            Context.revert(1, "NotAnOwner");
        }
    }
    private void checkAdminOrThrow(Address account) {
        if (!isAdmin(account)) {
            Context.revert(2, "InvalidAdmin");
        }
    }


    // ================================================
    // External methods
    // ================================================

    @External(readonly=true)
    public BigInteger balanceOf(Address _owner, BigInteger _id) {
        return balances.at(_id).getOrDefault(_owner, BigInteger.ZERO);
    }

    @External(readonly=true)
    public BigInteger[] balanceOfBatch(Address[] _owners, BigInteger[] _ids) {
        Context.require(_owners.length == _ids.length,
                "_owners array size must match with _ids array size");

        BigInteger[] balances = new BigInteger[_owners.length];
        for (int i = 0; i < _owners.length; i++) {
            balances[i] = balanceOf(_owners[i], _ids[i]);
        }
        return balances;
    }

    @External(readonly=true)
    public String tokenURI(BigInteger _id) {
        return tokenURIs.get(_id);
    }


    @External
    public void mint(BigInteger _id, String _uri, Address _to) {
        final Address caller = Context.getCaller();
        checkAdminOrThrow(caller);

        // mint tokens
        _mint(_to, _id, BigInteger.ONE);
        // set token URI
        _setTokenURI(_id, _uri);
    }

    @External
    public void addAdmin(Address _account) {
        checkAdminOrThrow(Context.getCaller());
        if (isAdmin(_account)) {
            Context.revert("AlreadyAdmin");
        }
        admins.set(_account, true);
    }

    @External
    public void removeAdmin(Address _account) {
        checkOwnerOrThrow();
        admins.set(_account, false);
    }

    @External
    public void setTokenURI(BigInteger _id, String _uri) {
        checkAdminOrThrow(Context.getCaller());
        _setTokenURI(_id, _uri);
    }

    // ================================================
    // Event Logs
    // ================================================

    @EventLog(indexed=3)
    public void TransferSingle(Address _operator, Address _from, Address _to, BigInteger _id, BigInteger _value) {}

    @EventLog(indexed=3)
    public void TransferBatch(Address _operator, Address _from, Address _to, byte[] _ids, byte[] _values) {}

    @EventLog(indexed=1)
    public void URI(BigInteger _id, String _value) {}

    // ================================================
    // Internal methods
    // ================================================

    /**
     * Convert a list of BigInteger to a RLP-encoded byte array
     *
     * @param ids A list of BigInteger
     * @return a RLP encoded byte array
     */
    protected static byte[] rlpEncode(BigInteger[] ids) {
        Context.require(ids != null);

        ByteArrayObjectWriter writer = Context.newByteArrayObjectWriter("RLPn");

        writer.beginList(ids.length);
        for (BigInteger v : ids) {
            writer.write(v);
        }
        writer.end();

        return writer.toByteArray();
    }

    private boolean isAdmin(Address account) {
        return admins.getOrDefault(account, false);
    }

    protected void _setTokenURI(BigInteger _id, String _uri) {
        Context.require(_uri.length() > 0, "Uri should be set");
        tokenURIs.set(_id, _uri);
        this.URI(_id, _uri);
    }

    private void _mintInternal(Address owner, BigInteger id, BigInteger amount) {
        Context.require(amount.compareTo(BigInteger.ZERO) > 0, "Invalid amount");

        BigInteger balance = balanceOf(owner, id);
        balances.at(id).set(owner, balance.add(amount));
    }

    protected void _mint(Address owner, BigInteger id, BigInteger amount) {
        _mintInternal(owner, id, amount);

        // emit transfer event for Mint semantic
        TransferSingle(owner, ZERO_ADDRESS, owner, id, amount);
    }

    protected void _mintBatch(Address owner, BigInteger[] ids, BigInteger[] amounts) {
        Context.require(ids.length == amounts.length, "id/amount pairs mismatch");

        for (int i = 0; i < ids.length; i++) {
            BigInteger id = ids[i];
            BigInteger amount = amounts[i];
            _mintInternal(owner, id, amount);
        }

        // emit transfer event for Mint semantic
        TransferBatch(owner, ZERO_ADDRESS, owner, rlpEncode(ids), rlpEncode(amounts));
    }

    private void _burnInternal(Address owner, BigInteger id, BigInteger amount) {
        Context.require(amount.compareTo(BigInteger.ZERO) > 0, "Invalid amount");

        BigInteger balance = balanceOf(owner, id);
        Context.require(balance.compareTo(amount) >= 0, "Insufficient funds");
        balances.at(id).set(owner, balance.subtract(amount));
    }

    protected void _burn(Address owner, BigInteger id, BigInteger amount) {
        _burnInternal(owner, id, amount);

        // emit transfer event for Burn semantic
        TransferSingle(owner, owner, ZERO_ADDRESS, id, amount);
    }

    protected void _burnBatch(Address owner, BigInteger[] ids, BigInteger[] amounts) {
        Context.require(ids.length == amounts.length, "id/amount pairs mismatch");

        for (int i = 0; i < ids.length; i++) {
            BigInteger id = ids[i];
            BigInteger amount = amounts[i];
            _burnInternal(owner, id, amount);
        }

        // emit transfer event for Burn semantic
        TransferBatch(owner, owner, ZERO_ADDRESS, rlpEncode(ids), rlpEncode(amounts));
    }
}
