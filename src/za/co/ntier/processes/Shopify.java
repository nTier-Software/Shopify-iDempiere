package za.co.ntier.processes;

import org.apache.http.client.utils.URIBuilder;
import org.compiere.model.PO;
import org.compiere.model.Query;
import org.compiere.process.SvrProcess;
import org.compiere.util.Env;

import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.icoderman.shopify.DefaultHttpClient;
import com.icoderman.shopify.HttpClient;
import za.co.ntier.model.X_zz_shopify;
import za.co.ntier.shopify.SfOrder;

/**
 *
 * Start a thread to collect unsynchronised orders from WooCommerce website
 *
 * @author yogan naidoo
 */

public class Shopify extends SvrProcess {

	public class MyRunnable implements Runnable {

		@Override
		public void run() {
			// Get Shopify defaults

			final PO sfDefaults;
			String whereClause = " isactive = 'Y' AND AD_Client_ID = ?";
			sfDefaults = new Query(getCtx(), X_zz_shopify.Table_Name, whereClause, null)
					.setParameters(new Object[] { Env.getAD_Client_ID(getCtx()) }).firstOnly();
			if (sfDefaults == null)
				throw new IllegalStateException("/nShopify Defaults need to be set on iDempiere /n");

			// Setup client
			/*
			 * OAuthConfig config = new OAuthConfig((String) wcDefaults.get_Value("url"),
			 * (String) wcDefaults.get_Value("consumerkey"), (String)
			 * wcDefaults.get_Value("consumersecret")); OAuthConfig config = new
			 * OAuthConfig("https://ntiertest.myshopify.com/admin",
			 * "36d41e61a77eaece34456a92287f119f",
			 * "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"); Woocommerce wooCommerce = new
			 * WooCommerceAPI(config, ApiVersionType.V202010);
			 */

			HttpClient client = new DefaultHttpClient((String) sfDefaults.get_Value("consumerkey"),
					(String) sfDefaults.get_Value("consumerSecret"));

			// String startDateTime = (LocalDateTime.parse("2020-12-01T02:07:00").atOffset(ZoneOffset.ofHours(-5)))
			String startDateTime = (LocalDateTime.parse((String) sfDefaults.get_Value("syncfrom"))
					.atOffset(ZoneOffset.ofHours(-5))).toString();

			URIBuilder builder = null;
			try {
				builder = new URIBuilder((String) sfDefaults.get_Value("url") + "/admin/orders.json");
			} catch (URISyntaxException e) {
				e.printStackTrace();
			}
			// builder.setParameter("updated_at_min", "2020-12-01T02:07:00-05:00")
			builder.setParameter("updated_at_min", startDateTime).setParameter("fulfillment_status", "shipped")
					.setParameter("status", "any");

			LinkedHashMap<?, ?> mapWcOrders = client.getAll(builder);
			List<?> wcOrders = (List<?>) mapWcOrders.get("orders");
			// Iterate through each order
			for (int i = 0; i < wcOrders.size(); i++) {
				Map<?, ?> order = (Map<?, ?>) wcOrders.get(i);
				SfOrder sfOrder = new SfOrder(getCtx(), get_TrxName(), sfDefaults);
				if (!sfOrder.createOrder(order)) {
					continue;
				}

				// Iterate through each order Line
				List<?> lines = (List<?>) order.get("line_items");
				for (int j = 0; j < lines.size(); j++) {
					Map<?, ?> line = (Map<?, ?>) lines.get(j);
					sfOrder.createOrderLine(line, order);
					Object name = line.get("name");
					System.out.println("Name of Product = " + name.toString());
				}
				sfOrder.createShippingCharge(order);
				sfOrder.createPosPayment(order);
				sfOrder.completeOrder();

				/*
				 * // Update syncedToIdempiere to 'yes' Map<String, Object> body = new
				 * HashMap<>(); List<Map<String, String>> listOfMetaData = new ArrayList();
				 * Map<String, String> metaData = new HashMap<>(); metaData.put("key",
				 * "syncedToIdempiere"); metaData.put("value", "yes");
				 * listOfMetaData.add(metaData);
				 * 
				 * body.put("meta_data", listOfMetaData); Map<?, ?> response =
				 * wooCommerce.update(EndpointBaseType.ORDERS.getValue(), id, body);
				 * System.out.println(response.toString());
				 */

				System.out.println("Order- " + order.get("order_number") + ": " + order);

			}
			// adjust startDateTime time to current time minus 10 min
			startDateTime = LocalDateTime.now().minusMinutes(10).format(DateTimeFormatter.ISO_DATE_TIME);
			Integer maxSize = 19;
			if(startDateTime.length() > maxSize ){
				startDateTime = startDateTime.substring(0, maxSize);
			}
			sfDefaults.set_ValueOfColumn("syncfrom", startDateTime);
			sfDefaults.saveEx();
		}

	}

	@Override
	protected void prepare() {
	}

	@Override
	protected String doIt() throws Exception {
		Thread thread = new Thread(new MyRunnable());
		thread.start();

		return "Synchronisation from Shopify initiated";
	}

}
