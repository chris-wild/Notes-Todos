import SwiftUI
import StoreKit

/// Credit packs for recipe conversion. Consumables need no Restore button; the small print
/// explains what a credit buys and where the balance lives (App Review wants both clear).
struct PaywallSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var ops: OpsStore

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Text("Credits remaining")
                        Spacer()
                        Text(ops.balance.map(String.init) ?? "—")
                            .fontWeight(.semibold)
                    }
                    Text("One credit turns one recipe page — a photo or a PDF page — into a shopping list. New installs start with 5 free credits. Credits never expire and follow your iCloud Keychain.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Buy credits") {
                    if ops.products.isEmpty {
                        if ops.productsLoaded {
                            Text("The credit packs are not available right now — check your connection and try again later.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        } else {
                            HStack {
                                ProgressView()
                                Text("Loading packs…")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    ForEach(ops.products, id: \.id) { product in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(product.displayName.isEmpty ? product.id : product.displayName)
                                if let credits = OpsStore.packCredits[product.id] {
                                    // Per-conversion price in the buyer's own currency —
                                    // the display name already says the count.
                                    Text("\((product.price / Decimal(credits)).formatted(product.priceFormatStyle)) per conversion")
                                        .font(.footnote)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button(product.displayPrice) {
                                Task { await ops.purchase(product) }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(ops.purchasing)
                        }
                    }
                }

                if let message = ops.message {
                    Section {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(Color.accentColor)
                    }
                }
            }
            .navigationTitle("Recipe conversions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .task {
                await ops.refreshBalance()
                // A launch-time load can race the network or Apple's product servers
                // (an empty SUCCESS during store propagation); opening the paywall is
                // the moment a stale empty list actually matters, so try again.
                if ops.products.isEmpty { await ops.loadProducts() }
            }
        }
    }
}
