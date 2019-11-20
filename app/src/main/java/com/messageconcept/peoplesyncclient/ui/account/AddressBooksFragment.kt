package at.bitfire.davdroid.ui.account

import android.content.Intent
import android.view.*
import at.bitfire.davdroid.R
import at.bitfire.davdroid.model.Collection
import at.bitfire.davdroid.ui.CreateAddressBookActivity
import kotlinx.android.synthetic.main.account_carddav_item.view.*

class AddressBooksFragment: CollectionsFragment() {

    override val noCollectionsStringId = R.string.account_no_address_books

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
            inflater.inflate(R.menu.carddav_actions, menu)

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (super.onOptionsItemSelected(item))
            return true

        if (item.itemId == R.id.create_address_book) {
            val intent = Intent(requireActivity(), CreateAddressBookActivity::class.java)
            intent.putExtra(CreateAddressBookActivity.EXTRA_ACCOUNT, accountModel.account)
            startActivity(intent)
            return true
        }

        return false
    }

    override fun createAdapter() = AddressBookAdapter(accountModel)


    class AddressBookViewHolder(
            parent: ViewGroup,
            accountModel: AccountActivity.Model
    ): CollectionViewHolder(parent, R.layout.account_carddav_item, accountModel) {

        override fun bindTo(item: Collection) {
            val v = itemView
            v.sync.isChecked = item.sync
            v.title.text = item.title()

            if (item.description.isNullOrBlank())
                v.description.visibility = View.GONE
            else {
                v.description.text = item.description
                v.description.visibility = View.VISIBLE
            }

            v.read_only.visibility = if (item.readOnly()) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                accountModel.toggleSync(item)
            }
            v.action_overflow.setOnClickListener(CollectionPopupListener(accountModel, item))
        }
    }

    class AddressBookAdapter(
            accountModel: AccountActivity.Model
    ): CollectionAdapter(accountModel) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            AddressBookViewHolder(parent, accountModel)

    }

}