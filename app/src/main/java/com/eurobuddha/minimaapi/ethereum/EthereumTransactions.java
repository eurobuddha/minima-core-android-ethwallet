package com.eurobuddha.minimaapi.ethereum;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.*;
import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Shared by Pandamonium's Ethereum clients. Private durable signed attempts, never keys.
 * Reuses AtomiX's account serialization and the wallets' acknowledged pre-post journal boundary.
 * Network failures retain the exact bytes; elapsed time never authorizes a different nonce use.
 */
public final class EthereumTransactions {
    public interface Rpc {
        BigInteger chain() throws Exception;
        BigInteger pending(String address) throws Exception;
        String broadcast(String signed) throws Exception;
        JSONObject receipt(String hash) throws Exception;
        JSONObject transaction(String hash) throws Exception;
    }
    public interface Store { String read(String key) throws Exception; void write(String key,String value) throws Exception; }
    public static final class Uncertain extends IOException {
        public final String hash;
        Uncertain(String hash) { super("Ethereum submission is unconfirmed. Its saved transaction will be checked or retried without creating a duplicate. Hash: "+hash);this.hash=hash; }
    }
    private static volatile EthereumTransactions shared;
    private final Store store;
    private final Map<String,Object> locks=new ConcurrentHashMap<>();
    private final Map<String,java.util.concurrent.atomic.AtomicLong> revisions=new ConcurrentHashMap<>();
    private static final BigInteger MAX=BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
    private static final BigInteger MAX_PRICE=BigInteger.valueOf(1_500_000_000_000L),MAX_GAS=BigInteger.valueOf(1_000_000);
    public EthereumTransactions(Store store) { this.store=Objects.requireNonNull(store); }
    public static synchronized void initialize(Context context) {
        if(shared==null)shared=new EthereumTransactions(new Database(context.getApplicationContext()));
    }
    public static EthereumTransactions shared() throws IOException {
        EthereumTransactions value=shared;
        if(value==null)throw new IOException("Ethereum transaction storage is unavailable. Reopen the app.");
        return value;
    }
    /** Immutable public intent for one nonce. Fee replacements have identical spending fields. */
    public static final class PendingIntent {
        public final BigInteger nonce,value,gas,price;
        public final String to,data;
        private PendingIntent(SignedRawTransaction tx){
            nonce=tx.getNonce();value=tx.getValue();gas=tx.getGasLimit();price=tx.getGasPrice();
            to=tx.getTo().toLowerCase(Locale.ROOT);data=Numeric.prependHexPrefix(tx.getData()).toLowerCase(Locale.ROOT);
        }
    }
    /** Read-only funding view. No receipt is settled, nonce freed, signature made or RPC called. */
    public static final class FundingSnapshot {
        public final List<PendingIntent> intents;
        private final java.util.concurrent.atomic.AtomicLong revision;
        private final long captured;
        private FundingSnapshot(List<PendingIntent> intents,java.util.concurrent.atomic.AtomicLong revision){
            this.intents=Collections.unmodifiableList(intents);this.revision=revision;captured=revision.get();
        }
        /** Safe on UI/delayed-post callbacks: never waits for an RPC or SQLite lock. */
        public boolean unchanged(){return captured==revision.get();}
    }
    public FundingSnapshot fundingSnapshot(long chain,String owner)throws Exception {
        if(chain<=0||owner==null||!owner.matches("0x[0-9a-fA-F]{40}"))throw new IOException("Invalid Ethereum funding account");
        String address=owner.toLowerCase(Locale.ROOT),key=chain+":"+address;
        synchronized(locks.computeIfAbsent(key,k->new Object())){
            List<PendingIntent> intents=new ArrayList<>();
            for(Row row:load(key,chain,address))if(!"PREPARED".equals(row.phase))intents.add(new PendingIntent(row.latest()));
            // Include previously settled rows: a lagging/reorganised chain view must not ignore them.
            // The funding reader excludes only nonces already consumed at its exact balance block.
            return new FundingSnapshot(intents,revisions.computeIfAbsent(key,k->new java.util.concurrent.atomic.AtomicLong()));
        }
    }

    /** Authorization throws the caller's existing localized session-change error. */
    public String send(Rpc rpc,Credentials credentials,long chain,String to,String data,BigInteger value,
                       BigInteger gas,BigInteger price,String operation,Runnable authorize) throws Exception {
        authorize.run();
        String address=credentials.getAddress().toLowerCase(Locale.ROOT);
        String destination=to==null?"":to.toLowerCase(Locale.ROOT);
        String payload=data==null||data.isEmpty()?"0x":data.toLowerCase(Locale.ROOT);
        BigInteger amount=value==null?BigInteger.ZERO:value;
        if(chain<=0||!address.matches("0x[0-9a-f]{40}")||!destination.matches("0x[0-9a-f]{40}")
                ||!payload.matches("0x(?:[0-9a-f]{2})*")||payload.length()>131074||amount.signum()<0||amount.bitLength()>256
                ||gas==null||gas.signum()<=0||gas.compareTo(MAX_GAS)>0||price==null||price.signum()<=0||price.compareTo(MAX_PRICE)>0)
            throw new IOException("Invalid Ethereum transaction fields or fee.");
        String key=chain+":"+address;
        synchronized(locks.computeIfAbsent(key,k->new Object())) {
            authorize.run();
            // Invalidate an advertised funding review as soon as a new send enters this account.
            revisions.computeIfAbsent(key,k->new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            if(!BigInteger.valueOf(chain).equals(rpc.chain()))throw new IOException("Ethereum RPC is connected to a different chain.");
            BigInteger pending=rpc.pending(address);nonce(pending,false);
            authorize.run();
            List<Row> rows=load(key,chain,address);
            // PREPARED proves broadcast was never entered. A prior process cannot resume it.
            if(rows.removeIf(row->"PREPARED".equals(row.phase)))save(key,rows,chain,address);
            String intent=intent(destination,payload,amount,gas);
            Row target=null;
            for(Row row:rows) {
                boolean sameOperation=operation!=null&&operation.equals(row.operation)&&(!"pending".equals(operation)||intent.equals(row.intent));
                // A refreshed gas estimate does not turn an unknown payment into a new payment.
                boolean repeatUnknown=operation==null&&"POSTING".equals(row.phase)
                        &&intent(destination,payload,amount,row.latest().getGasLimit()).equals(row.intent);
                if((sameOperation||repeatUnknown)&&!"REVERTED".equals(row.phase)) {
                    if(!intent.equals(row.intent)&&!repeatUnknown)throw new IOException("Saved Ethereum operation differs from this request.");
                    target=row;
                }
            }
            if(target!=null) {
                boolean settled=settle(rpc,target,address);
                if(settled) {
                    save(key,rows,chain,address);
                    if("CONFIRMED".equals(target.phase)) {
                        // Generic automatic approvals may be needed again after their old allowance was used.
                        if(!"pending".equals(operation))return target.confirmed;
                    }
                    target=null;
                }
            }
            if(target!=null) {
                SignedRawTransaction saved=target.latest();
                // Retain the original gas limit and payment. A retry may not exceed either
                // fee field just reviewed, even when replaying an already signed attempt.
                if(gas.compareTo(saved.getGasLimit())<0||price.compareTo(saved.getGasPrice())<0)
                    throw new Uncertain(target.latestHash());
                gas=saved.getGasLimit();
            }
            // Keep earlier nonce gaps recoverable with their original signed transaction. Never replace
            // one with another payment/lock, even if another endpoint reports a lower pending count.
            for(Row row:rows)if(row!=target&&active(row)) {
                if(settle(rpc,row,address)){save(key,rows,chain,address);continue;}
                if(known(rpc,row,address)==null) {
                    if(row.nonce.compareTo(pending)<0)throw new Uncertain(row.latestHash());
                    authorize.run();
                    broadcast(rpc,row,key,rows,chain,address);
                }
            }
            if(target!=null) {
                target.phase="POSTING";target.confirmed="";
                if(target.nonce.compareTo(pending)<0&&known(rpc,target,address)==null)throw new Uncertain(target.latestHash());
                BigInteger oldPrice=target.latest().getGasPrice();
                // A fee replacement has exactly the same nonce, recipient, value and calldata.
                // The caller's newly reviewed/automatic fee remains the ceiling; never raise it here.
                if(price.compareTo(oldPrice.multiply(BigInteger.valueOf(110)).add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)))>=0) {
                    authorize.run();
                    String signed=sign(credentials,chain,target.nonce,destination,payload,amount,gas,price);
                    authorize.run();
                    target.versions.add(signed);target.phase="POSTING";save(key,rows,chain,address);
                    return broadcast(rpc,target,key,rows,chain,address);
                }
                authorize.run();
                save(key,rows,chain,address);
                return broadcast(rpc,target,key,rows,chain,address);
            }
            BigInteger allocation=pending;
            for(Row row:rows)if(row.nonce.compareTo(allocation)>=0)allocation=row.nonce.add(BigInteger.ONE);
            nonce(allocation,true);authorize.run();
            String signed=sign(credentials,chain,allocation,destination,payload,amount,gas,price);
            authorize.run();
            Row row=new Row();row.nonce=allocation;row.operation=operation==null?"":operation;row.intent=intent;
            row.phase="PREPARED";row.versions.add(signed);rows.add(row);
            save(key,rows,chain,address);
            authorize.run();
            row.phase="POSTING";save(key,rows,chain,address);
            return broadcast(rpc,row,key,rows,chain,address);
        }
    }
    private String broadcast(Rpc rpc,Row row,String key,List<Row> rows,long chain,String address) throws Exception {
        String hash=row.latestHash();
        try {
            String returned=rpc.broadcast(row.versions.get(row.versions.size()-1));
            if(!hash.equalsIgnoreCase(returned))throw new IOException("Unexpected Ethereum transaction hash");
        } catch(Exception failed) {
            // An 'already known' or lost ACK may still describe our exact signed transaction.
            String found=known(rpc,row,address);
            if(found==null)throw new Uncertain(hash);
            hash=found;
        }
        row.phase="ACCEPTED";
        try {save(key,rows,chain,address);}catch(Exception unavailable){throw new Uncertain(hash);}
        return hash;
    }
    private static boolean active(Row row){return "POSTING".equals(row.phase)||"ACCEPTED".equals(row.phase);}
    private static String sign(Credentials key,long chain,BigInteger nonce,String to,String data,BigInteger amount,BigInteger gas,BigInteger price) {
        return Numeric.toHexString(TransactionEncoder.signMessage(RawTransaction.createTransaction(nonce,price,gas,to,amount,data),chain,key));
    }
    private static String intent(String to,String data,BigInteger value,BigInteger gas) {
        return to.toLowerCase(Locale.ROOT)+":"+Numeric.cleanHexPrefix(data).toLowerCase(Locale.ROOT)+":"+value+":"+gas;
    }
    private static void nonce(BigInteger value,boolean sendable)throws IOException {
        if(value==null||value.signum()<0||value.compareTo(MAX)>0||sendable&&value.equals(MAX))throw new IOException("Ethereum transaction nonce is outside the sendable range");
    }
    private static BigInteger quantity(Object value)throws IOException {
        if(!(value instanceof String)||!((String)value).matches("0x(?:0|[1-9a-fA-F][0-9a-fA-F]*)"))throw new IOException("Invalid Ethereum RPC quantity");
        return new BigInteger(((String)value).substring(2),16);
    }
    private static boolean hex(Object value,int bytes){return value instanceof String&&((String)value).matches("0x[0-9a-fA-F]{"+(bytes*2)+"}");}
    /** Match an exact locally hashed transaction; a pending count alone is not its receipt. */
    private static String known(Rpc rpc,Row row,String address) {
        for(String raw:row.versions)try {
            String hash=Hash.sha3(raw);JSONObject tx=rpc.transaction(hash);if(tx==null)continue;
            SignedRawTransaction signed=(SignedRawTransaction)TransactionDecoder.decode(raw);
            if(hash.equalsIgnoreCase(tx.optString("hash"))&&address.equalsIgnoreCase(tx.optString("from"))
                    &&signed.getTo().equalsIgnoreCase(tx.optString("to"))&&signed.getNonce().equals(quantity(tx.opt("nonce")))
                    &&signed.getValue().equals(quantity(tx.opt("value")))&&signed.getGasLimit().equals(quantity(tx.opt("gas")))
                    &&signed.getGasPrice().equals(quantity(tx.opt("gasPrice")))
                    &&Numeric.cleanHexPrefix(signed.getData()).equalsIgnoreCase(Numeric.cleanHexPrefix(tx.optString("input")))
                    &&(!tx.has("chainId")||BigInteger.valueOf(signed.getChainId()).equals(quantity(tx.opt("chainId")))))return hash;
        }catch(Exception unavailable){/* Retain uncertainty. */}
        return null;
    }
    private static boolean settle(Rpc rpc,Row row,String address)throws Exception {
        for(String raw:row.versions) {
            String hash=Hash.sha3(raw);JSONObject receipt;
            try { receipt=rpc.receipt(hash); } catch(Exception unavailable) { throw new Uncertain(row.latestHash()); }
            if(receipt==null)continue;
            SignedRawTransaction tx=(SignedRawTransaction)TransactionDecoder.decode(raw);
            if(!hash.equalsIgnoreCase(receipt.optString("transactionHash"))||!hex(receipt.opt("blockHash"),32)
                    ||!address.equalsIgnoreCase(receipt.optString("from"))||!tx.getTo().equalsIgnoreCase(receipt.optString("to"))
                    ||quantity(receipt.opt("blockNumber")).signum()<0)throw new IOException("Invalid Ethereum transaction receipt");
            BigInteger status=quantity(receipt.opt("status"));
            if(!status.equals(BigInteger.ZERO)&&!status.equals(BigInteger.ONE))throw new IOException("Invalid Ethereum receipt status");
            row.confirmed=hash;row.phase=status.signum()==1?"CONFIRMED":"REVERTED";return true;
        }
        // Reorg or lagging endpoint: retain nonce ownership and recover only the original bytes.
        return false;
    }
    private static final class Row {
        BigInteger nonce;String operation="",intent="",phase="",confirmed="";List<String> versions=new ArrayList<>();
        SignedRawTransaction latest(){return(SignedRawTransaction)TransactionDecoder.decode(versions.get(versions.size()-1));}
        String latestHash(){return Hash.sha3(versions.get(versions.size()-1));}
        JSONObject json()throws JSONException{return new JSONObject().put("nonce",nonce.toString()).put("operation",operation).put("intent",intent).put("phase",phase).put("confirmed",confirmed).put("versions",new JSONArray(versions));}
    }
    private List<Row> load(String key,long chain,String address)throws Exception {
        String source=store.read(key);return decode(source==null?"[]":source,chain,address);
    }
    private static String text(JSONObject object,String key)throws IOException {
        Object value=object.opt(key);if(!(value instanceof String))throw new IOException("Invalid Ethereum transaction journal");return(String)value;
    }
    private static List<Row> decode(String source,long chain,String address)throws Exception {
        JSONTokener tokener=new JSONTokener(source);Object parsed=tokener.nextValue();
        if(!(parsed instanceof JSONArray)||tokener.nextClean()!=0)throw new IOException("Invalid Ethereum transaction journal; original data preserved");
        JSONArray values=(JSONArray)parsed;List<Row> rows=new ArrayList<>();Set<BigInteger> nonces=new HashSet<>();
        for(int i=0;i<values.length();i++) {
            JSONObject value=values.getJSONObject(i);Row row=new Row();String number=text(value,"nonce");
            if(!number.matches("0|[1-9][0-9]{0,19}"))throw new IOException("Invalid saved Ethereum nonce");
            row.nonce=new BigInteger(number);nonce(row.nonce,true);if(!nonces.add(row.nonce))throw new IOException("Duplicate saved Ethereum nonce");
            row.operation=text(value,"operation");row.intent=text(value,"intent");row.phase=text(value,"phase");row.confirmed=text(value,"confirmed");
            if(!Arrays.asList("PREPARED","POSTING","ACCEPTED","CONFIRMED","REVERTED").contains(row.phase))throw new IOException("Invalid Ethereum journal phase");
            JSONArray versions=value.getJSONArray("versions");if(versions.length()==0)throw new IOException("Missing Ethereum signed attempt");
            BigInteger previous=BigInteger.ZERO;
            for(int v=0;v<versions.length();v++) {
                Object encoded=versions.get(v);if(!(encoded instanceof String)||!((String)encoded).matches("0x(?:[0-9a-f]{2})+")||((String)encoded).length()>140000)throw new IOException("Invalid saved Ethereum transaction");
                String raw=(String)encoded;RawTransaction decoded=TransactionDecoder.decode(raw);
                if(!(decoded instanceof SignedRawTransaction))throw new IOException("Unsigned Ethereum journal entry");
                SignedRawTransaction tx=(SignedRawTransaction)decoded;
                if(!Long.valueOf(chain).equals(tx.getChainId())||!address.equalsIgnoreCase(tx.getFrom())||!row.nonce.equals(tx.getNonce())
                        ||!row.intent.equals(intent(tx.getTo(),tx.getData(),tx.getValue(),tx.getGasLimit()))
                        ||tx.getGasPrice().compareTo(previous)<=0||tx.getGasPrice().compareTo(MAX_PRICE)>0
                        ||tx.getGasLimit().signum()<=0||tx.getGasLimit().compareTo(MAX_GAS)>0)throw new IOException("Inconsistent Ethereum journal entry");
                previous=tx.getGasPrice();row.versions.add(raw);
            }
            if(!row.confirmed.isEmpty()&&!row.versions.stream().anyMatch(raw->Hash.sha3(raw).equals(row.confirmed)))throw new IOException("Unknown saved Ethereum receipt hash");
            if(("CONFIRMED".equals(row.phase)||"REVERTED".equals(row.phase))!=!row.confirmed.isEmpty())throw new IOException("Missing saved Ethereum settlement");
            rows.add(row);
        }
        return rows;
    }
    private void save(String key,List<Row> rows,long chain,String address)throws Exception {
        JSONArray array=new JSONArray();for(Row row:rows)array.put(row.json());String data=array.toString();decode(data,chain,address);
        revisions.computeIfAbsent(key,k->new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        store.write(key,data);
    }
    /** SQLite provides the same atomic acknowledged write boundary used by NFT/UTXO journals. */
    private static final class Database extends SQLiteOpenHelper implements Store {
        Database(Context context){super(context,"pandamonium_eth_submissions.db",null,1);}
        public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE submissions (account TEXT PRIMARY KEY NOT NULL, state TEXT NOT NULL)");}
        public void onUpgrade(SQLiteDatabase db,int from,int to){throw new IllegalStateException("Unsupported Ethereum journal migration");}
        public String read(String key){try(Cursor c=getReadableDatabase().rawQuery("SELECT state FROM submissions WHERE account=?",new String[]{key})){return c.moveToFirst()?c.getString(0):"[]";}}
        public void write(String key,String state){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{ContentValues v=new ContentValues();v.put("account",key);v.put("state",state);if(db.insertWithOnConflict("submissions",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Ethereum journal write failed");db.setTransactionSuccessful();}finally{db.endTransaction();}}
    }
}
